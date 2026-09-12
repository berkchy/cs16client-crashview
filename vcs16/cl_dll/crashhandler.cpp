#ifdef __ANDROID__

#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/time.h>
#include <unwind.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <time.h>

#include "crashhandler.h"

static char s_crashLogPath[256] = {0};
static volatile sig_atomic_t s_inCrash = 0;
static char s_engineVersion[64] = {0};
static char s_patcherVersion[64] = {0};

// ─── async-signal-safe helpers ──────────────────────────────────────────────

static size_t safeStrlen(const char *str, size_t maxLen) {
	size_t len = 0;
	while (len < maxLen && str[len]) len++;
	return len;
}

static void safeStrcat(char *dst, const char *src, size_t dstSize) {
	size_t dstLen = safeStrlen(dst, dstSize - 1);
	size_t srcLen = safeStrlen(src, dstSize - 1);
	if (dstLen + srcLen >= dstSize) srcLen = dstSize - dstLen - 1;
	memcpy(dst + dstLen, src, srcLen);
	dst[dstLen + srcLen] = '\0';
}

static void safeIntToHex(char *dst, unsigned long val, size_t dstSize) {
	const char hex[] = "0123456789abcdef";
	char buf[20];
	int i = 0;
	if (val == 0) { dst[0] = '0'; dst[1] = '\0'; return; }
	while (val > 0 && i < 18) { buf[i++] = hex[val & 0xf]; val >>= 4; }
	size_t len = (size_t)i;
	if (len >= dstSize) len = dstSize - 1;
	for (size_t j = 0; j < len; j++) dst[j] = buf[len - 1 - j];
	dst[len] = '\0';
}

static void safeIntToStr(char *dst, int val, size_t dstSize) {
	char buf[16];
	int i = 0, neg = 0;
	if (val < 0) { neg = 1; val = -val; }
	if (val == 0) buf[i++] = '0';
	while (val > 0 && i < 15) { buf[i++] = '0' + (val % 10); val /= 10; }
	if (neg && i < 15) buf[i++] = '-';
	size_t len = (size_t)i;
	if (len >= dstSize) len = dstSize - 1;
	for (size_t j = 0; j < len; j++) dst[j] = buf[len - 1 - j];
	dst[len] = '\0';
}

static const char *getSignalName(int sig) {
	switch (sig) {
		case SIGILL:  return "SIGILL (Illegal instruction)";
		case SIGSEGV: return "SIGSEGV (Segmentation fault)";
		case SIGBUS:  return "SIGBUS (Bus error)";
		case SIGABRT: return "SIGABRT (Abort)";
		case SIGFPE:  return "SIGFPE (Floating point exception)";
		default:      return "UNKNOWN";
	}
}

static int writeStr(int fd, const char *s) {
	return write(fd, s, safeStrlen(s, 4096));
}

// ─── unwind backtrace ───────────────────────────────────────────────────────
// Read the crashed thread's frame pointer from ucontext registers, then walk
// the frame chain. This works even inside signal handlers where inline asm
// would only see the handler's own stack frames.

static int getBacktrace(void **buffer, int maxFrames, void *ucontext) {
	int count = 0;
	if (!ucontext) return 0;
	mcontext_t *mctx = &((ucontext_t *)ucontext)->uc_mcontext;

#if defined(__aarch64__)
	void **fp = (void **)mctx->regs[29];
	while (count < maxFrames && fp && !((unsigned long)fp & 0xf)) {
		void *ra = (void *)fp[1];
		if (!ra) break;
		buffer[count++] = ra;
		void **prev = (void **)*fp;
		if (prev <= fp) break;
		fp = prev;
	}
#elif defined(__arm__)
	void **fp = (void **)mctx->arm_fp;
	while (count < maxFrames && fp && !((unsigned long)fp & 0x3)) {
		void *ra = (void *)fp[1];
		if (!ra) break;
		buffer[count++] = ra;
		void **prev = (void **)*fp;
		if (prev <= fp) break;
		fp = prev;
	}
#else
	(void)mctx;
	struct BacktraceState { void **cur; void **end; int depth; };
	auto cb = [](_Unwind_Context *ctx, void *arg) -> _Unwind_Reason_Code {
		auto *s = (BacktraceState *)arg;
		void *ip = (void *)_Unwind_GetIP(ctx);
		if (ip && s->cur < s->end) { *s->cur++ = ip; s->depth++; }
		if (s->depth > 256) return _URC_END_OF_STACK;
		return _URC_NO_REASON;
	};
	BacktraceState state = { buffer, buffer + maxFrames, 0 };
	_Unwind_Backtrace(cb, &state);
	count = state.depth;
#endif
	return count;
}

// ─── ELF symbol resolution via dl_iterate_phdr ──────────────────────────────

struct LibInfo {
	void *target_addr;     // address we're resolving
	unsigned long base;    // dlpi_addr (load bias)
	const ElfW(Phdr) *phdr;
	int phnum;
	char name[256];
	void *dynsym;
	void *dynstr;
	size_t dynstr_size;
	size_t sym_entsize;
	char build_id[64];
	int has_build_id;
	int found;
};

static int resolveCallback(struct dl_phdr_info *info, size_t size, void *data) {
	struct LibInfo *li = (struct LibInfo *)data;
	unsigned long base = (unsigned long)info->dlpi_addr;

	for (int i = 0; i < info->dlpi_phnum; i++) {
		const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
		if (ph->p_type != PT_LOAD) continue;

		unsigned long seg_start = base + ph->p_vaddr;
		unsigned long seg_end = seg_start + ph->p_memsz;

		if ((unsigned long)li->target_addr >= seg_start && (unsigned long)li->target_addr < seg_end) {
			li->base = base;
			li->phdr = info->dlpi_phdr;
			li->phnum = info->dlpi_phnum;

			if (info->dlpi_name && info->dlpi_name[0]) {
				const char *slash = strrchr(info->dlpi_name, '/');
				if (slash) safeStrcat(li->name, slash + 1, sizeof(li->name));
				else safeStrcat(li->name, info->dlpi_name, sizeof(li->name));
			} else {
				safeStrcat(li->name, "[main]", sizeof(li->name));
			}

			// Parse PT_DYNAMIC for .dynsym/.dynstr
			for (int j = 0; j < info->dlpi_phnum; j++) {
				const ElfW(Phdr) *dyn_ph = &info->dlpi_phdr[j];
				if (dyn_ph->p_type != PT_DYNAMIC) continue;

				const ElfW(Dyn) *dyn = (const ElfW(Dyn) *)(base + dyn_ph->p_vaddr);
				while (dyn->d_tag != DT_NULL) {
					switch (dyn->d_tag) {
						case DT_SYMTAB:  li->dynsym = (void *)dyn->d_un.d_ptr; break;
						case DT_STRTAB:  li->dynstr = (void *)dyn->d_un.d_ptr; break;
						case DT_STRSZ:   li->dynstr_size = dyn->d_un.d_val; break;
						case DT_SYMENT:  li->sym_entsize = dyn->d_un.d_val; break;
					}
					dyn++;
				}
				break;
			}

			// Parse PT_NOTE for build-id (NT_GNU_BUILD_ID = type 3, "GNU" name)
			for (int j = 0; j < info->dlpi_phnum; j++) {
				const ElfW(Phdr) *note_ph = &info->dlpi_phdr[j];
				if (note_ph->p_type != PT_NOTE) continue;

				const unsigned char *nb = (const unsigned char *)(base + note_ph->p_vaddr);
				const unsigned char *ne = nb + note_ph->p_filesz;
				const unsigned char *p = nb;

				while (p + 12 <= ne) {
					uint32_t namesz = *(const uint32_t *)p;
					uint32_t descsz = *(const uint32_t *)(p + 4);
					uint32_t type   = *(const uint32_t *)(p + 8);
					p += 12;
					const char *name = (const char *)p;
					p += (namesz + 3) & ~3;
					const unsigned char *desc = p;
					p += (descsz + 3) & ~3;

					if (namesz == 4 && memcmp(name, "GNU", 4) == 0 && type == 3) {
						li->has_build_id = 1;
						char *out = li->build_id;
						for (size_t k = 0; k < descsz && k < 20; k++) {
							const char hx[] = "0123456789abcdef";
							*out++ = hx[(desc[k] >> 4) & 0xf];
							*out++ = hx[desc[k] & 0xf];
						}
						*out = '\0';
					}
				}
				break;
			}

			li->found = 1;
			return 1;
		}
	}
	return 0;
}

// Find closest function symbol in .dynsym (from loaded memory, no allocation)
static void findDynsymSymbol(struct LibInfo *li, unsigned long addr, char *out, size_t outSize) {
	out[0] = '\0';
	if (!li->dynsym || !li->dynstr || li->sym_entsize == 0) return;

	const char *strtab = (const char *)li->dynstr;
	unsigned long base = li->base;

	const char *best_name = NULL;
	unsigned long best_addr = 0;

	// Walk .dynsym entries safely (limit to reasonable count)
	for (size_t i = 0; i < 8192; i++) {
		const ElfW(Sym) *s = (const ElfW(Sym) *)((const char *)li->dynsym + i * li->sym_entsize);

		// Safety: stop if we've gone past the string table
		if ((const void *)s >= (const void *)li->dynstr) break;

		int type = ELF64_ST_TYPE(s->st_info);
		if (type != STT_FUNC) continue;
		if (s->st_shndx == SHN_UNDEF) continue;
		if (s->st_value == 0) continue;

		unsigned long func_addr = base + s->st_value;
		if (func_addr <= addr && func_addr > best_addr) {
			best_addr = func_addr;
			if (s->st_name < li->dynstr_size) {
				best_name = strtab + s->st_name;
			}
		}
	}

	if (best_name && best_name[0]) {
		unsigned long offset = addr - best_addr;
		out[0] = '\0';
		safeStrcat(out, best_name, outSize);
		if (offset > 0) {
			safeStrcat(out, "+0x", outSize);
			char hex[20];
			safeIntToHex(hex, offset, sizeof(hex));
			safeStrcat(out, hex, outSize);
		}
	}
}

// Try to read .symtab from disk for full symbol table (uses open/read/mmap, no malloc)
// Returns 1 on success, 0 on failure
static int tryReadSymtab(const char *so_path,
                          ElfW(Sym) **out_sym, char **out_str, size_t *out_count) {
	*out_sym = NULL; *out_str = NULL; *out_count = 0;

	int fd = open(so_path, O_RDONLY);
	if (fd < 0) return 0;

	// Read ELF header
	ElfW(Ehdr) ehdr;
	if (read(fd, &ehdr, sizeof(ehdr)) != sizeof(ehdr)) { close(fd); return 0; }
	if (memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) { close(fd); return 0; }

	// Read all section headers into stack buffer (max ~128KB)
	size_t sh_total = (size_t)ehdr.e_shnum * (size_t)ehdr.e_shentsize;
	if (sh_total > 131072 || ehdr.e_shnum == 0) { close(fd); return 0; }

	char shdr_buf[131072];
	if (lseek(fd, ehdr.e_shoff, SEEK_SET) < 0) { close(fd); return 0; }
	if (read(fd, shdr_buf, sh_total) != (ssize_t)sh_total) { close(fd); return 0; }

	ElfW(Shdr) *shdr = (ElfW(Shdr) *)shdr_buf;

	// Read shstrtab
	if (ehdr.e_shstrndx >= ehdr.e_shnum) { close(fd); return 0; }
	ElfW(Shdr) *shstr = &shdr[ehdr.e_shstrndx];
	if (shstr->sh_size > 65536) { close(fd); return 0; }

	char shstrtab[65536];
	if (lseek(fd, shstr->sh_offset, SEEK_SET) < 0) { close(fd); return 0; }
	if (read(fd, shstrtab, shstr->sh_size) != (ssize_t)shstr->sh_size) { close(fd); return 0; }

	// Find .symtab and .strtab
	ElfW(Shdr) *symtab_sh = NULL;
	ElfW(Shdr) *strtab_sh = NULL;

	for (int i = 0; i < ehdr.e_shnum; i++) {
		if (shdr[i].sh_name >= shstr->sh_size) continue;
		const char *name = shstrtab + shdr[i].sh_name;

		if (shdr[i].sh_type == SHT_SYMTAB && strcmp(name, ".symtab") == 0) {
			symtab_sh = &shdr[i];
		}
		if (shdr[i].sh_type == SHT_STRTAB && strcmp(name, ".strtab") == 0) {
			strtab_sh = &shdr[i];
		}
	}

	if (!symtab_sh || !strtab_sh) { close(fd); return 0; }
	if (symtab_sh->sh_size > 1048576 || strtab_sh->sh_size > 1048576) { close(fd); return 0; }

	// Read .symtab and .strtab using mmap (async-signal-safe on Linux/Android)
	void *symtab = mmap(NULL, symtab_sh->sh_size, PROT_READ, MAP_PRIVATE, fd, symtab_sh->sh_offset);
	void *strtab = mmap(NULL, strtab_sh->sh_size, PROT_READ, MAP_PRIVATE, fd, strtab_sh->sh_offset);

	if (symtab == MAP_FAILED || strtab == MAP_FAILED) {
		if (symtab != MAP_FAILED) munmap(symtab, symtab_sh->sh_size);
		if (strtab != MAP_FAILED) munmap(strtab, strtab_sh->sh_size);
		close(fd);
		return 0;
	}

	*out_sym = (ElfW(Sym) *)symtab;
	*out_str = (char *)strtab;
	*out_count = symtab_sh->sh_size / symtab_sh->sh_entsize;

	close(fd);
	return 1;
}

// Find closest function in .symtab (from mmap'd disk file)
static void findSymtabSymbol(ElfW(Sym) *sym, const char *strtab, size_t count,
                              unsigned long base, unsigned long addr,
                              char *out, size_t outSize) {
	out[0] = '\0';

	const char *best_name = NULL;
	unsigned long best_addr = 0;
	unsigned long best_size = 0;

	for (size_t i = 0; i < count; i++) {
		const ElfW(Sym) *s = &sym[i];
		int type = ELF64_ST_TYPE(s->st_info);
		if (type != STT_FUNC) continue;
		if (s->st_shndx == SHN_UNDEF || s->st_value == 0) continue;

		unsigned long func_addr = base + s->st_value;
		if (func_addr <= addr && func_addr > best_addr) {
			best_addr = func_addr;
			best_size = s->st_size;
			best_name = strtab + s->st_name;
		}
	}

	if (best_name && best_name[0]) {
		unsigned long func_off = addr - best_addr;
		out[0] = '\0';
		safeStrcat(out, best_name, outSize);
		if (func_off > 0) {
			safeStrcat(out, "+0x", outSize);
			char hex[20];
			safeIntToHex(hex, func_off, sizeof(hex));
			safeStrcat(out, hex, outSize);
		}
		if (best_size > 0) {
			safeStrcat(out, " [size=0x", outSize);
			char hex[20];
			safeIntToHex(hex, best_size, sizeof(hex));
			safeStrcat(out, hex, outSize);
			safeStrcat(out, "]", outSize);
		}
	}
}

// ─── memory map dump (async-signal-safe via open/read) ──────────────────────

static void dumpMaps(int fd, unsigned long addr, unsigned long addr30, unsigned long addr16) {
	write(fd, "\n--- Memory Maps (executable) ---\n", 33);

	int maps_fd = open("/proc/self/maps", O_RDONLY);
	if (maps_fd < 0) return;

	char buf[4096];
	int n;
	while ((n = read(maps_fd, buf, sizeof(buf))) > 0) {
		char *line = buf;
		char *end = buf + n;
		while (line < end) {
			char *eol = line;
			while (eol < end && *eol != '\n' && *eol != '\0') eol++;

			// Only executable segments (perms at offset 3 == 'x')
			if (eol - line > 6 && line[3] == 'x') {
				write(fd, line, eol - line);
				write(fd, "\n", 1);

				// Parse start/end addresses
				unsigned long start = 0, end_addr = 0;
				char *p = line;
				while (p < eol && *p != '-') { start = start * 16 + (*p >= 'a' ? *p - 'a' + 10 : *p >= 'A' ? *p - 'A' + 10 : *p - '0'); p++; }
				p++;
				while (p < eol && *p != ' ') { end_addr = end_addr * 16 + (*p >= 'a' ? *p - 'a' + 10 : *p >= 'A' ? *p - 'A' + 10 : *p - '0'); p++; }

				if (addr >= start && addr < end_addr) write(fd, "    <<< FAULT ADDR\n", 19);
				if (addr30 >= start && addr30 < end_addr) write(fd, "    <<< LR (x30)\n", 17);
				if (addr16 >= start && addr16 < end_addr) write(fd, "    <<< x16\n", 12);
			}

			if (eol < end) line = eol + 1;
			else break;
		}
	}
	close(maps_fd);
}

// ─── enhanced address resolver ──────────────────────────────────────────────

static void resolveAddressEnhanced(char *buf, size_t bufSize, void *addr, int log_fd) {
	struct LibInfo lib;
	memset(&lib, 0, sizeof(lib));
	lib.target_addr = addr;

	dl_iterate_phdr(resolveCallback, &lib);

	if (!lib.found) {
		buf[0] = '\0';
		safeStrcat(buf, "0x", bufSize);
		char hex[20];
		safeIntToHex(hex, (unsigned long)addr, sizeof(hex));
		safeStrcat(buf, hex, bufSize);
		return;
	}

	unsigned long offset = (unsigned long)addr - lib.base;

	buf[0] = '\0';
	safeStrcat(buf, "[", bufSize);
	safeStrcat(buf, lib.name, bufSize);
	safeStrcat(buf, "+0x", bufSize);
	char hex[20];
	safeIntToHex(hex, offset, sizeof(hex));
	safeStrcat(buf, hex, bufSize);
	safeStrcat(buf, "] ", bufSize);

	// Resolve .so path from /proc/self/maps
	char so_path[256] = {0};
	{
		int maps_fd = open("/proc/self/maps", O_RDONLY);
		if (maps_fd >= 0) {
			char maps_buf[4096];
			int maps_len = read(maps_fd, maps_buf, sizeof(maps_buf) - 1);
			close(maps_fd);
			if (maps_len > 0) {
				maps_buf[maps_len] = '\0';
				char *mline = maps_buf;
				while (mline < maps_buf + maps_len) {
					char *eol = mline;
					while (eol < maps_buf + maps_len && *eol != '\n') eol++;
					if (strstr(mline, lib.name)) {
						char *p = mline;
						for (int s = 0; s < 5 && p < eol; s++) {
							while (p < eol && *p != ' ') p++;
							if (p < eol) p++;
						}
						size_t plen = (size_t)(eol - p);
						if (plen > 0 && plen < sizeof(so_path)) {
							memcpy(so_path, p, plen);
							so_path[plen] = '\0';
						}
						break;
					}
					mline = eol + 1;
				}
			}
		}
	}

	// Try .symtab from disk (has all functions with sizes)
	char symtab_result[256] = {0};
	if (so_path[0]) {
		ElfW(Sym) *sym = NULL;
		char *str = NULL;
		size_t count = 0;
		if (tryReadSymtab(so_path, &sym, &str, &count)) {
			findSymtabSymbol(sym, str, count, lib.base, (unsigned long)addr, symtab_result, sizeof(symtab_result));
		}
	}

	// Try .dynsym from loaded memory
	char dynsym_result[256] = {0};
	findDynsymSymbol(&lib, (unsigned long)addr, dynsym_result, sizeof(dynsym_result));

	// Pick best: .symtab > .dynsym
	const char *best = symtab_result[0] ? symtab_result : (dynsym_result[0] ? dynsym_result : NULL);
	if (best) {
		safeStrcat(buf, best, bufSize);
	}

	// Write build-id + addr2line hint to crash log
	if (lib.has_build_id) {
		char bid[128];
		bid[0] = '\0';
		safeStrcat(bid, "  Build-ID: ", sizeof(bid));
		safeStrcat(bid, lib.build_id, sizeof(bid));
		safeStrcat(bid, "\n", sizeof(bid));
		write(log_fd, bid, strlen(bid));

		if (so_path[0]) {
			char hint[512];
			hint[0] = '\0';
			safeStrcat(hint, "  addr2line -e ", sizeof(hint));
			safeStrcat(hint, so_path, sizeof(hint));
			safeStrcat(hint, " -f 0x", sizeof(hint));
			safeStrcat(hint, hex, sizeof(hint));
			safeStrcat(hint, "\n", sizeof(hint));
			write(log_fd, hint, strlen(hint));
		}
	}
}

// ─── crash handler ──────────────────────────────────────────────────────────

static void crashHandler(int sig, siginfo_t *info, void *ucontext) {
	if (s_inCrash) _exit(1);
	s_inCrash = 1;

	int fd = open(s_crashLogPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (fd < 0) _exit(1);

	// Header
	writeStr(fd, "\n=== CS16Client CRASH ===\n");

	// Signal
	writeStr(fd, "Signal: ");
	writeStr(fd, getSignalName(sig));
	writeStr(fd, "\n");

	// Fault address
	if (info && info->si_addr) {
		char line[64] = "Fault addr: 0x";
		char hex[20];
		safeIntToHex(hex, (unsigned long)info->si_addr, sizeof(hex));
		safeStrcat(line, hex, sizeof(line));
		safeStrcat(line, "\n", sizeof(line));
		writeStr(fd, line);
	}

	// PID/TID
	{
		char line[64] = "PID: ";
		char num[16];
		safeIntToStr(num, getpid(), sizeof(num));
		safeStrcat(line, num, sizeof(line));
		safeStrcat(line, " TID: ", sizeof(line));
		safeIntToStr(num, gettid(), sizeof(num));
		safeStrcat(line, num, sizeof(line));
		safeStrcat(line, "\n", sizeof(line));
		writeStr(fd, line);
	}

	// Date/time
	{
		time_t now = time(NULL);
		struct tm tm_buf;
		localtime_r(&now, &tm_buf);
		char dt[64];
		strftime(dt, sizeof(dt), "%Y-%m-%d %H:%M:%S", &tm_buf);
		writeStr(fd, "Time: ");
		writeStr(fd, dt);
		writeStr(fd, "\n");
	}

	// Device info via system properties
	{
		char prop[256];
		writeStr(fd, "\n--- Device ---\n");

		if (__system_property_get("ro.product.brand", prop) > 0) {
			writeStr(fd, "Brand: "); writeStr(fd, prop); writeStr(fd, "\n");
		}
		if (__system_property_get("ro.product.model", prop) > 0) {
			writeStr(fd, "Model: "); writeStr(fd, prop); writeStr(fd, "\n");
		}
		if (__system_property_get("ro.product.device", prop) > 0) {
			writeStr(fd, "Device: "); writeStr(fd, prop); writeStr(fd, "\n");
		}
		if (__system_property_get("ro.product.board", prop) > 0) {
			writeStr(fd, "Board: "); writeStr(fd, prop); writeStr(fd, "\n");
		}
		if (__system_property_get("ro.hardware.chipname", prop) > 0) {
			writeStr(fd, "SoC: "); writeStr(fd, prop); writeStr(fd, "\n");
		} else if (__system_property_get("ro.hardware", prop) > 0) {
			writeStr(fd, "Hardware: "); writeStr(fd, prop); writeStr(fd, "\n");
		}
		if (__system_property_get("ro.product.cpu.abilist", prop) > 0) {
			writeStr(fd, "CPU ABI: "); writeStr(fd, prop); writeStr(fd, "\n");
		} else if (__system_property_get("ro.product.cpu.abi", prop) > 0) {
			writeStr(fd, "CPU ABI: "); writeStr(fd, prop); writeStr(fd, "\n");
		}
		if (__system_property_get("ro.build.version.release", prop) > 0) {
			writeStr(fd, "Android: "); writeStr(fd, prop);
			if (__system_property_get("ro.build.version.sdk", prop) > 0) {
				writeStr(fd, " (SDK "); writeStr(fd, prop); writeStr(fd, ")");
			}
			writeStr(fd, "\n");
		}
		if (__system_property_get("ro.build.display.id", prop) > 0) {
			writeStr(fd, "Build: "); writeStr(fd, prop); writeStr(fd, "\n");
		}
	}

	// Engine + Patcher version
	if (s_engineVersion[0]) {
		writeStr(fd, "Engine: "); writeStr(fd, s_engineVersion); writeStr(fd, "\n");
	}
	if (s_patcherVersion[0]) {
		writeStr(fd, "Patcher: "); writeStr(fd, s_patcherVersion); writeStr(fd, "\n");
	}

	// Registers + extract key values
	unsigned long x30v = 0, x16v = 0;
#if defined(__aarch64__)
	mcontext_t *mctx = ucontext ? &((ucontext_t *)ucontext)->uc_mcontext : NULL;
	if (mctx) {
		x30v = mctx->regs[30];
		x16v = mctx->regs[16];
	}
#elif defined(__arm__)
	mcontext_t *mctx = ucontext ? &((ucontext_t *)ucontext)->uc_mcontext : NULL;
	if (mctx) {
		x30v = mctx->arm_lr;
	}
#elif defined(__x86_64__)
	mcontext_t *mctx = ucontext ? &((ucontext_t *)ucontext)->uc_mcontext : NULL;
#endif

	// Unwind backtrace
	void *frames[64];
	int frameCount = getBacktrace(frames, 64, ucontext);

	writeStr(fd, "\n--- Backtrace ---\n");

	for (int i = 0; i < frameCount; i++) {
		char line[1024];
		line[0] = '\0';

		safeStrcat(line, "  #", sizeof(line));
		char num[16];
		safeIntToStr(num, i, sizeof(num));
		safeStrcat(line, num, sizeof(line));
		safeStrcat(line, " pc ", sizeof(line));

		char hex[20];
		safeIntToHex(hex, (unsigned long)frames[i], sizeof(hex));
		safeStrcat(line, hex, sizeof(line));

		// Enhanced resolution
		char resolved[512];
		resolved[0] = '\0';
		resolveAddressEnhanced(resolved, sizeof(resolved), frames[i], fd);

		if (resolved[0]) {
			safeStrcat(line, " ", sizeof(line));
			safeStrcat(line, resolved, sizeof(line));
		}
		safeStrcat(line, "\n", sizeof(line));
		writeStr(fd, line);
	}

	// Register dump
	if (mctx) {
		writeStr(fd, "\n--- Registers ---\n");
#if defined(__aarch64__)
		{
			const char *regNames[] = {
				"x0","x1","x2","x3","x4","x5","x6","x7",
				"x8","x9","x10","x11","x12","x13","x14","x15",
				"x16","x17","x18","x19","x20","x21","x22","x23",
				"x24","x25","x26","x27","x28","x29","x30","sp",
				"pc","pstate"
			};
			for (int i = 0; i < 34; i++) {
				char line[64] = "  ";
				safeStrcat(line, regNames[i], sizeof(line));
				safeStrcat(line, " = 0x", sizeof(line));
				char hex[20];
				safeIntToHex(hex, mctx->regs[i], sizeof(hex));
				safeStrcat(line, hex, sizeof(line));
				safeStrcat(line, "\n", sizeof(line));
				writeStr(fd, line);
			}
		}
#elif defined(__arm__)
		{
			const char *regNames[] = {
				"r0","r1","r2","r3","r4","r5","r6","r7",
				"r8","r9","r10","r11","r12","sp","lr","pc","cpsr"
			};
			unsigned long regVals[] = {
				mctx->arm_r0, mctx->arm_r1, mctx->arm_r2, mctx->arm_r3,
				mctx->arm_r4, mctx->arm_r5, mctx->arm_r6, mctx->arm_r7,
				mctx->arm_r8, mctx->arm_r9, mctx->arm_r10, mctx->arm_fp,
				mctx->arm_ip, mctx->arm_sp, mctx->arm_lr, mctx->arm_pc,
				mctx->arm_cpsr
			};
			for (int i = 0; i < 17; i++) {
				char line[64] = "  ";
				safeStrcat(line, regNames[i], sizeof(line));
				safeStrcat(line, " = 0x", sizeof(line));
				char hex[20];
				safeIntToHex(hex, regVals[i], sizeof(hex));
				safeStrcat(line, hex, sizeof(line));
				safeStrcat(line, "\n", sizeof(line));
				writeStr(fd, line);
			}
		}
#elif defined(__x86_64__)
		{
			const char *regNames[] = {
				"rax","rbx","rcx","rdx","rdi","rsi","rbp","rsp",
				"r8","r9","r10","r11","r12","r13","r14","r15",
				"rip","eflags"
			};
			unsigned long regVals[] = {
				mctx->gregs[REG_RAX], mctx->gregs[REG_RBX],
				mctx->gregs[REG_RCX], mctx->gregs[REG_RDX],
				mctx->gregs[REG_RDI], mctx->gregs[REG_RSI],
				mctx->gregs[REG_RBP], mctx->gregs[REG_RSP],
				mctx->gregs[REG_R8], mctx->gregs[REG_R9],
				mctx->gregs[REG_R10], mctx->gregs[REG_R11],
				mctx->gregs[REG_R12], mctx->gregs[REG_R13],
				mctx->gregs[REG_R14], mctx->gregs[REG_R15],
				mctx->gregs[REG_RIP], mctx->gregs[REG_EFL]
			};
			for (int i = 0; i < 18; i++) {
				char line[64] = "  ";
				safeStrcat(line, regNames[i], sizeof(line));
				safeStrcat(line, " = 0x", sizeof(line));
				char hex[20];
				safeIntToHex(hex, regVals[i], sizeof(hex));
				safeStrcat(line, hex, sizeof(line));
				safeStrcat(line, "\n", sizeof(line));
				writeStr(fd, line);
			}
		}
#endif
	}

	dumpMaps(fd, (unsigned long)info->si_addr, x30v, x16v);

	writeStr(fd, "=== END CRASH ===\n");
	close(fd);
	_exit(1);
}

// ─── init ───────────────────────────────────────────────────────────────────

static struct sigaction s_oldHandlers[32];

void CrashHandler_Init(void) {
	// Set up an alternate signal stack so the handler has its own stack
	// even when the crashed thread's stack is corrupted or overflowed.
	static char s_signalStack[SIGSTKSZ];
	stack_t ss;
	memset(&ss, 0, sizeof(ss));
	ss.ss_sp = s_signalStack;
	ss.ss_size = SIGSTKSZ;
	ss.ss_flags = 0;
	sigaltstack(&ss, NULL);

	struct sigaction sa;
	memset(&sa, 0, sizeof(sa));
	sa.sa_sigaction = crashHandler;
	sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
	sigemptyset(&sa.sa_mask);

	int sigs[] = { SIGILL, SIGSEGV, SIGBUS, SIGABRT, SIGFPE };
	for (int i = 0; i < 5; i++) {
		sigaction(sigs[i], &sa, &s_oldHandlers[sigs[i]]);
	}
}

void CrashHandler_SetGameDir(const char *gamedir) {
	s_crashLogPath[0] = '\0';
	if (gamedir && gamedir[0]) {
		safeStrcat(s_crashLogPath, gamedir, sizeof(s_crashLogPath));
		safeStrcat(s_crashLogPath, "/crash.log", sizeof(s_crashLogPath));
	} else {
		safeStrcat(s_crashLogPath, "/sdcard/cs16client/crash.log", sizeof(s_crashLogPath));
	}
}

void CrashHandler_SetEngineVersion(const char *ver) {
	s_engineVersion[0] = '\0';
	if (ver && ver[0]) safeStrcat(s_engineVersion, ver, sizeof(s_engineVersion));
}

void CrashHandler_SetPatcherVersion(const char *ver) {
	s_patcherVersion[0] = '\0';
	if (ver && ver[0]) safeStrcat(s_patcherVersion, ver, sizeof(s_patcherVersion));
}

#endif // __ANDROID__
