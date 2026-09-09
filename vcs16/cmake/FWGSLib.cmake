# Minimal implementation of the FWGS cmake module macros that 3rdparty/mainui_cpp
# relies on. The genuine FWGSLib.cmake lives in the cs16-client / xash3d build
# trees; this fork builds native libs standalone (no engine buildsystem), so here
# we provide only what mainui actually calls:
#   fwgs_fix_default_msvc_settings()
#   fwgs_add_compile_options(<lang> <opts...>)
#   fwgs_set_default_properties(<target>)
#   fwgs_install(<target>)

function(fwgs_fix_default_msvc_settings)
	if(MSVC)
		foreach(_opt IN LISTS CMAKE_MSVC_DEFAULT_RUNTIME_LIBRARY_CONFIG)
		endforeach()
	endif()
endfunction()

function(fwgs_add_compile_options)
	set(_lang "${ARGV0}")
	if(_lang STREQUAL "C")
		list(REMOVE_AT ARGV 0)
		add_compile_options(${ARGV})
		add_compile_options(${ARGV})
	endif()
endfunction()

function(fwgs_set_default_properties _target)
	# mainui names the library itself (menu / xashmenu[64]); make sure the
	# shared object keeps a sane SONAME and is PIC on all toolchains.
	if(NOT TARGET ${_target})
		return()
	endif()
	set_target_properties(${_target} PROPERTIES
		POSITION_INDEPENDENT_CODE ON
		C_VISIBILITY_PRESET hidden
		CXX_VISIBILITY_PRESET hidden
		VISIBILITY_INLINES_HIDDEN ON
	)
endfunction()

function(fwgs_install _target)
	if(NOT TARGET ${_target})
		return()
	endif()
	install(TARGETS ${_target}
		LIBRARY DESTINATION lib
		RUNTIME DESTINATION bin
		ARCHIVE DESTINATION lib
	)
endfunction()