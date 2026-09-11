/***
*
*	Copyright (c) 1996-2002, Valve LLC. All rights reserved.
*	
*	This product contains software technology licensed from Id 
*	Software, Inc. ("Id Technology").  Id Technology (c) 1996 Id Software, Inc. 
*	All Rights Reserved.
*
*   Use, distribution, and modification of this source code and/or resulting
*   object code is restricted to non-commercial enhancements to products from
*   Valve LLC.  All other use, distribution, or modification is prohibited
*   without written permission from Valve LLC.
*
****/
//
//  hud_update.cpp
//

#include <math.h>
#include "hud.h"
#include "cl_util.h"
#include <stdlib.h>
#include <memory.h>

int CL_ButtonBits( int );
void CL_ResetButtonBits( int bits );

extern float v_idlescale;
extern void HUD_SetCmdBits( int bits );

int CHud::UpdateClientData(client_data_t *cdata, float time)
{
	m_vecOrigin = cdata->origin;
	m_vecAngles = cdata->viewangles;

	m_iKeyBits = CL_ButtonBits( 0 );
	m_iWeaponBits = cdata->iWeaponBits;

	Think();

	if ( cl_smoothfov && cl_smoothfov->value > 0.0f )
	{
		// exponentially ease the rendered FOV toward the target
		float k = 1.0f - expf( -cl_smoothfov->value * time );
		if ( k > 1.0f ) k = 1.0f;
		m_flSmoothedFOV += ( (float)m_iFOV - m_flSmoothedFOV ) * k;
		cdata->fov = (int)( m_flSmoothedFOV + 0.5f );
	}
	else
	{
		cdata->fov = m_iFOV;
		m_flSmoothedFOV = m_iFOV;
	}
	
	v_idlescale = m_iConcussionEffect;

	CL_ResetButtonBits( m_iKeyBits );

	// return 1 if in anything in the client_data struct has been changed, 0 otherwise
	return 1;
}


