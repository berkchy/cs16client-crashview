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

#include "crashhandler.h"

static char s_crashLogPath[256] = {0};
static volatile sig_atomic_t s_inCrash = 0;

struct BacktraceState {
	void **current;
	void **end;
	int depth;
};

static _Unwind_Reason_Code unwindCallback(struct _Unwind_Context *context, void *arg) {
	BacktraceState *state = (BacktraceState *)arg;
	void *ip = (void *)_Unwind_GetIP(context);
	if (ip) {
		if (state->current < state->end) {
			*state->current++ = ip;
			state->depth++;
		}
		if (state->depth > 256) return _URC_END_OF_STACK;
	}
	return _URC_NO_REASON;
}

static int getBacktrace(void **buffer, int maxFrames) {
	BacktraceState state;
	state.current = buffer;
	state.end = buffer + maxFrames;
	state.depth = 0;
	_Unwind_Backtrace(unwindCallback, &state);
	return state.depth;
}

static void safeStrcat(char *dst, const char *src, size_t dstSize) {
	size_t dstLen = strlen(dst);
	size_t srcLen = strlen(src);
	if (dstLen + srcLen >= dstSize) srcLen = dstSize - dstLen - 1;
	memcpy(dst + dstLen, src, srcLen);
	dst[dstLen + srcLen] = '\0';
}

static void safeIntToHex(char *dst, unsigned long val, size_t dstSize) {
	const char hex[] = "0123456789abcdef";
	char buf[20];
	int i = 0;

	if (val == 0) {
		dst[0] = '0';
		dst[1] = '\0';
		return;
	}

	while (val > 0 && i < 18) {
		buf[i++] = hex[val & 0xf];
		val >>= 4;
	}

	size_t len = (size_t)i;
	if (len >= dstSize) len = dstSize - 1;
	for (size_t j = 0; j < len; j++) {
		dst[j] = buf[len - 1 - j];
	}
	dst[len] = '\0';
}

static void safeIntToStr(char *dst, int val, size_t dstSize) {
	char buf[16];
	int i = 0;
	int neg = 0;

	if (val < 0) { neg = 1; val = -val; }
	if (val == 0) { buf[i++] = '0'; }
	while (val > 0 && i < 15) {
		buf[i++] = '0' + (val % 10);
		val /= 10;
	}
	if (neg && i < 15) buf[i++] = '-';

	size_t len = (size_t)i;
	if (len >= dstSize) len = dstSize - 1;
	for (size_t j = 0; j < len; j++) {
		dst[j] = buf[len - 1 - j];
	}
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

static void resolveAddress(char *buf, size_t bufSize, void *addr) {
	Dl_info info;
	if (dladdr(addr, &info)) {
		if (info.dli_fname && info.dli_sname) {
			unsigned long offset = (unsigned long)addr - (unsigned long)info.dli_fbase;
			buf[0] = '\0';
			safeStrcat(buf, "[", bufSize);
			const char *slash = strrchr(info.dli_fname, '/');
			if (slash) safeStrcat(buf, slash + 1, bufSize);
			else safeStrcat(buf, info.dli_fname, bufSize);
			safeStrcat(buf, "+0x", bufSize);
			char hex[20];
			safeIntToHex(hex, offset, sizeof(hex));
			safeStrcat(buf, hex, bufSize);
			safeStrcat(buf, "] ", bufSize);
			safeStrcat(buf, info.dli_sname, bufSize);
			return;
		}
		if (info.dli_fname) {
			unsigned long offset = (unsigned long)addr - (unsigned long)info.dli_fbase;
			buf[0] = '\0';
			safeStrcat(buf, "[", bufSize);
			const char *slash = strrchr(info.dli_fname, '/');
			if (slash) safeStrcat(buf, slash + 1, bufSize);
			else safeStrcat(buf, info.dli_fname, bufSize);
			safeStrcat(buf, "+0x", bufSize);
			char hex[20];
			safeIntToHex(hex, offset, sizeof(hex));
			safeStrcat(buf, hex, bufSize);
			safeStrcat(buf, "]", bufSize);
			return;
		}
	}
	buf[0] = '\0';
	safeStrcat(buf, "0x", bufSize);
	char hex[20];
	safeIntToHex(hex, (unsigned long)addr, sizeof(hex));
	safeStrcat(buf, hex, bufSize);
}

static void dumpMaps(int fd, unsigned long addr, unsigned long addr30, unsigned long addr16) {
	char header[] = "\n--- Memory Maps ---\n";
	write(fd, header, sizeof(header) - 1);

	char line[512];
	FILE *f = fopen("/proc/self/maps", "r");
	if (!f) return;
	while (fgets(line, sizeof(line), f)) {
		unsigned long start = 0, end = 0;
		char perms[8] = {0};
		sscanf(line, "%lx-%lx %7s", &start, &end, perms);
		if (perms[2] == 'x') {
			size_t len = strlen(line);
			if (len > 0 && line[len - 1] == '\n') line[len - 1] = '\0';
			write(fd, line, strlen(line));
			if (addr >= start && addr < end)
				write(fd, "    <<< PC/FAULT (SIGILL here)\n", 31);
			if (addr30 >= start && addr30 < end)
				write(fd, "    <<< LR (x30 caller)\n", 23);
			if (addr16 >= start && addr16 < end)
				write(fd, "    <<< x16\n", 12);
			write(fd, "\n", 1);
		}
	}
	fclose(f);
}

static void crashHandler(int sig, siginfo_t *info, void *ucontext) {
	if (s_inCrash) _exit(1);
	s_inCrash = 1;

	int fd = open(s_crashLogPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (fd < 0) _exit(1);

	const char *sigName = getSignalName(sig);

	char header[] = "\n=== CS16Client CRASH ===\n";
	write(fd, header, sizeof(header) - 1);

	// Signal info
	char line[512];
	line[0] = '\0';
	safeStrcat(line, "Signal: ", sizeof(line));
	safeStrcat(line, sigName, sizeof(line));
	safeStrcat(line, "\n", sizeof(line));
	write(fd, line, strlen(line));

	// Fault address
	line[0] = '\0';
	safeStrcat(line, "Fault addr: 0x", sizeof(line));
	char hex[20];
	safeIntToHex(hex, (unsigned long)info->si_addr, sizeof(hex));
	safeStrcat(line, hex, sizeof(line));
	safeStrcat(line, "\n", sizeof(line));
	write(fd, line, strlen(line));

	// PID/TID
	line[0] = '\0';
	safeStrcat(line, "PID: ", sizeof(line));
	char num[16];
	safeIntToStr(num, getpid(), sizeof(num));
	safeStrcat(line, num, sizeof(line));
	safeStrcat(line, " TID: ", sizeof(line));
	safeIntToStr(num, gettid(), sizeof(num));
	safeStrcat(line, num, sizeof(line));
	safeStrcat(line, "\n", sizeof(line));
	write(fd, line, strlen(line));

	// Backtrace header
	char btHeader[] = "\n--- Backtrace ---\n";
	write(fd, btHeader, sizeof(btHeader) - 1);

	// Unwind backtrace
	void *frames[64];
	int frameCount = getBacktrace(frames, 64);

	for (int i = 0; i < frameCount; i++) {
		line[0] = '\0';
		safeStrcat(line, "  #", sizeof(line));
		char num[16];
		safeIntToStr(num, i, sizeof(num));
		safeStrcat(line, num, sizeof(line));
		safeStrcat(line, " pc ", sizeof(line));

		char hex[20];
		safeIntToHex(hex, (unsigned long)frames[i], sizeof(hex));
		safeStrcat(line, hex, sizeof(line));

		// Resolve symbol
		char resolved[256];
		resolveAddress(resolved, sizeof(resolved), frames[i]);
		if (resolved[0]) {
			safeStrcat(line, " ", sizeof(line));
			safeStrcat(line, resolved, sizeof(line));
		}
		safeStrcat(line, "\n", sizeof(line));
		write(fd, line, strlen(line));
	}

	// Register dump for ARM64
	if (ucontext) {
		char regHeader[] = "\n--- Registers ---\n";
		write(fd, regHeader, sizeof(regHeader) - 1);

#if defined(__aarch64__)
		mcontext_t *mctx = &((ucontext_t *)ucontext)->uc_mcontext;
		const char *regNames[] = {
			"x0","x1","x2","x3","x4","x5","x6","x7",
			"x8","x9","x10","x11","x12","x13","x14","x15",
			"x16","x17","x18","x19","x20","x21","x22","x23",
			"x24","x25","x26","x27","x28","x29","x30","sp",
			"pc","pstate"
		};
		for (int i = 0; i < 34; i++) {
			line[0] = '\0';
			safeStrcat(line, "  ", sizeof(line));
			safeStrcat(line, regNames[i], sizeof(line));
			safeStrcat(line, " = 0x", sizeof(line));
			char hex[20];
			safeIntToHex(hex, mctx->regs[i], sizeof(hex));
			safeStrcat(line, hex, sizeof(line));
			safeStrcat(line, "\n", sizeof(line));
			write(fd, line, strlen(line));
		}
#elif defined(__arm__)
		mcontext_t *mctx = &((ucontext_t *)ucontext)->uc_mcontext;
		const char *regNames[] = {
			"r0","r1","r2","r3","r4","r5","r6","r7",
			"r8","r9","r10","r11","r12","sp","lr","pc",
			"cpsr"
		};
		// ARM32 sigcontext exposes named arm_* fields instead of a regs[]
		// array (aarch64 layout). r0..r10 map 1:1; r11=arm_fp, r12=arm_ip.
		unsigned long regVals[] = {
			mctx->arm_r0, mctx->arm_r1, mctx->arm_r2, mctx->arm_r3,
			mctx->arm_r4, mctx->arm_r5, mctx->arm_r6, mctx->arm_r7,
			mctx->arm_r8, mctx->arm_r9, mctx->arm_r10, mctx->arm_fp,
			mctx->arm_ip, mctx->arm_sp, mctx->arm_lr, mctx->arm_pc,
			mctx->arm_cpsr
		};
		for (int i = 0; i < 17; i++) {
			line[0] = '\0';
			safeStrcat(line, "  ", sizeof(line));
			safeStrcat(line, regNames[i], sizeof(line));
			safeStrcat(line, " = 0x", sizeof(line));
			char hex[20];
			safeIntToHex(hex, regVals[i], sizeof(hex));
			safeStrcat(line, hex, sizeof(line));
			safeStrcat(line, "\n", sizeof(line));
			write(fd, line, strlen(line));
		}
#elif defined(__x86_64__)
		mcontext_t *mctx = &((ucontext_t *)ucontext)->uc_mcontext;
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
			line[0] = '\0';
			safeStrcat(line, "  ", sizeof(line));
			safeStrcat(line, regNames[i], sizeof(line));
			safeStrcat(line, " = 0x", sizeof(line));
			char hex[20];
			safeIntToHex(hex, regVals[i], sizeof(hex));
			safeStrcat(line, hex, sizeof(line));
			safeStrcat(line, "\n", sizeof(line));
			write(fd, line, strlen(line));
		}
#endif
	}

	unsigned long x30v = 0, x16v = 0;
#if defined(__aarch64__)
	if (ucontext) {
		mcontext_t *mctx = &((ucontext_t *)ucontext)->uc_mcontext;
		x30v = mctx->regs[30];
		x16v = mctx->regs[16];
	}
#endif
	dumpMaps(fd, (unsigned long)info->si_addr, x30v, x16v);

	char footer[] = "=== END CRASH ===\n";
	write(fd, footer, sizeof(footer) - 1);
	close(fd);

	// Re-raise with default handler to generate tombstone
	struct sigaction sa;
	memset(&sa, 0, sizeof(sa));
	sa.sa_handler = SIG_DFL;
	sigemptyset(&sa.sa_mask);
	sigaction(sig, &sa, NULL);
	kill(getpid(), sig);
}

static struct sigaction s_oldHandlers[32];

void CrashHandler_Init(void) {
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

#endif // __ANDROID__
