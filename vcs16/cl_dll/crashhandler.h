#ifndef CRASHHANDLER_H
#define CRASHHANDLER_H

#ifdef __ANDROID__
void CrashHandler_Init(void);
void CrashHandler_SetGameDir(const char *gamedir);
void CrashHandler_SetEngineVersion(const char *ver);
void CrashHandler_SetPatcherVersion(const char *ver);
#else
static inline void CrashHandler_Init(void) {}
static inline void CrashHandler_SetGameDir(const char *gamedir) {}
static inline void CrashHandler_SetEngineVersion(const char *ver) {}
static inline void CrashHandler_SetPatcherVersion(const char *ver) {}
#endif

#endif // CRASHHANDLER_H
