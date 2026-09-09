// Compiler shims required by the engine SDK headers (common/xash3d_types.h,
// engine/menu_int.h) when building 3rdparty/mainui_cpp outside of the engine
// buildsystem. Forced via `-include` in the BUILD_MAINUI cmake block.
#ifndef MAINUI_CONFIG_H
#define MAINUI_CONFIG_H

// xash3d_types.h does `#include STDINT_H`; the engine build normally passes
// -DSTDINT_H=<cstdint>. Provide it here instead of a paren-laden define that
// breaks the generated makefile shell lines.
#ifndef STDINT_H
	#define STDINT_H <stdint.h>
	#include <stdint.h>
#endif

// menu_int.h annotates printf-like callbacks with `_format( argno )`; the
// engine build normally provides this via a compiler flag.
#ifndef _format
	#if defined( __GNUC__ ) || defined( __clang__ )
		#define _format( S ) __attribute__(( format( printf, S, ( S ) + 1 ) ))
	#else
		#define _format( S )
	#endif
#endif

#endif // MAINUI_CONFIG_H