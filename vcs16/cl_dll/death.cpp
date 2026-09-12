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
// death notice
//
#include "hud.h"
#include "cl_util.h"
#include "parsemsg.h"

#include <string.h>
#include <stdio.h>
#include <vector>
#include <math.h>
#include "draw_util.h"
#include "strl.h"

float color[3];

struct DeathNoticeItem {
	char szKiller[MAX_PLAYER_NAME_LENGTH*2];
	char szVictim[MAX_PLAYER_NAME_LENGTH*2];
	int iId;	// the index number of the associated sprite
	bool bSuicide;
	bool bTeamKill;
	bool bNonPlayerKill;
	float flDisplayTime;
	float *KillerColor;
	float *VictimColor;
	int iHeadShotId;
	float flCreateTime;
	float flSlotMoveTime;
	int iSlot;
	int iPrevSlot;
	bool bKillerIsLocal;
};

static int DEATHNOTICE_DISPLAY_TIME = 6;

#define DEATHNOTICE_TOP		32
#define DEATHNOTICE_RIGHT_PAD	4
#define DEATHNOTICE_BG_PAD_X	6
#define DEATHNOTICE_BG_PAD_Y	2

static std::vector<DeathNoticeItem> g_DeathNoticeList;

cvar_t *cl_killsound;
cvar_t *cl_killsound_path;
static cvar_t *cl_killfeed_smooth;

static int DeathNotice_RowGap( const cvar_t *rowGapCvar )
{
	const int defaultGap = 24;
	const int minGap = 12;
	const int maxGap = 48;
	int gap = rowGapCvar ? (int)( rowGapCvar->value + 0.5f ) : defaultGap;
	if( gap < minGap )
		gap = minGap;
	if( gap > maxGap )
		gap = maxGap;
	return gap;
}

static int DeathNotice_RightPad( const cvar_t *xCvar )
{
	const int defaultPad = 4;
	const int minPad = 0;
	const int maxPad = ScreenWidth;
	int pad = xCvar ? (int)( xCvar->value + 0.5f ) : defaultPad;
	if( pad < minPad )
		pad = minPad;
	if( pad > maxPad )
		pad = maxPad;
	return pad;
}

static int DeathNotice_YOffset( const cvar_t *yCvar )
{
	int offset = yCvar ? (int)( yCvar->value + 0.5f ) : 0;
	const int minOffset = -ScreenHeight;
	const int maxOffset = ScreenHeight;
	if( offset < minOffset )
		offset = minOffset;
	if( offset > maxOffset )
		offset = maxOffset;
	return offset;
}

static float DeathNotice_ClampFloat( float value, float minValue, float maxValue )
{
	if( value < minValue )
		return minValue;
	if( value > maxValue )
		return maxValue;
	return value;
}

static int DeathNotice_ClampInt( int value, int minValue, int maxValue )
{
	if( value < minValue )
		return minValue;
	if( value > maxValue )
		return maxValue;
	return value;
}

static float DeathNotice_EaseOutCubic( float t )
{
	t = DeathNotice_ClampFloat( t, 0.0f, 1.0f );
	float inv = 1.0f - t;
	return 1.0f - inv * inv * inv;
}

static int DeathNotice_RowBaseY( int slot, const cvar_t *rowGapCvar, const cvar_t *yCvar )
{
	const int rowGap = DeathNotice_RowGap( rowGapCvar );
	const int yOffset = DeathNotice_YOffset( yCvar );

	if( !g_iUser1 )
		return YRES(DEATHNOTICE_TOP) + 2 + yOffset + ( rowGap * slot );

	return ScreenHeight / 5 + 2 + yOffset + ( rowGap * slot );
}

static int DeathNotice_RectHeight( int weaponId, int headshotId )
{
	int height = 13;
	int weaponHeight = gHUD.GetSpriteRect( weaponId ).Height();
	if( weaponHeight > height )
		height = weaponHeight;
	if( headshotId )
	{
		int headshotHeight = gHUD.GetSpriteRect( headshotId ).Height();
		if( headshotHeight > height )
			height = headshotHeight;
	}

	return height;
}

static void DeathNotice_DrawSoftBackground( int x, int y, int wide, int tall, int alpha, const cvar_t *softnessCvar )
{
	alpha = DeathNotice_ClampInt( alpha, 0, 255 );
	if( alpha <= 0 || wide <= 0 || tall <= 0 )
		return;

	int softness = softnessCvar ? (int)( softnessCvar->value + 0.5f ) : 100;
	softness = DeathNotice_ClampInt( softness, 0, 100 );

	int rad = (int)( softness * 8.0f / 100.0f + 0.5f );
	rad = DeathNotice_ClampInt( rad, 0, wide / 2 );
	rad = DeathNotice_ClampInt( rad, 0, tall / 2 );

	if( rad < 1 )
	{
		FillRGBABlend( x, y, wide, tall, 0, 0, 0, alpha );
		return;
	}

	// corner arcs with a soft alpha falloff (scoreboard-style rounded panel)
	for( int dy = 0; dy < rad; dy++ )
	{
		int dist = rad - dy;
		int halfW = (int)( sqrtf( (float)rad * rad - (float)dist * dist ) + 0.5f );
		int skip = rad - halfW;

		// outer corner band fades in gently toward the center
		int edgeAlpha = (int)( alpha * ( 0.35f + 0.65f * (float)dy / (float)( rad - 1 ) ) + 0.5f );

		if( skip > 0 )
		{
			FillRGBABlend( x, y + dy, skip, 1, 0, 0, 0, edgeAlpha );
			FillRGBABlend( x + wide - skip, y + dy, skip, 1, 0, 0, 0, edgeAlpha );
			FillRGBABlend( x, y + tall - 1 - dy, skip, 1, 0, 0, 0, edgeAlpha );
			FillRGBABlend( x + wide - skip, y + tall - 1 - dy, skip, 1, 0, 0, 0, edgeAlpha );
		}

		if( wide - skip * 2 > 0 )
		{
			FillRGBABlend( x + skip, y + dy, wide - skip * 2, 1, 0, 0, 0, alpha );
			FillRGBABlend( x + skip, y + tall - 1 - dy, wide - skip * 2, 1, 0, 0, 0, alpha );
		}
	}

	if( tall - rad * 2 > 0 )
		FillRGBABlend( x, y + rad, wide, tall - rad * 2, 0, 0, 0, alpha );
}

static void DeathNotice_SetConsoleColor( const float *source, float brightness )
{
	brightness = DeathNotice_ClampFloat( brightness, 0.0f, 1.0f );
	if( source )
		DrawUtils::SetConsoleTextColor( source[0] * brightness, source[1] * brightness, source[2] * brightness );
	else
		DrawUtils::SetConsoleTextColor( brightness, brightness, brightness );
}

static void DeathNotice_GetSpriteColor( const DeathNoticeItem &notice, float brightness, int &r, int &g, int &b )
{
	brightness = DeathNotice_ClampFloat( brightness, 0.0f, 1.0f );

	if( notice.bKillerIsLocal )
	{
		r = g = b = (int)( 255.0f * brightness + 0.5f );
		return;
	}

	if( notice.KillerColor )
	{
		r = (int)( notice.KillerColor[0] * 255.0f * brightness + 0.5f );
		g = (int)( notice.KillerColor[1] * 255.0f * brightness + 0.5f );
		b = (int)( notice.KillerColor[2] * 255.0f * brightness + 0.5f );
		return;
	}

	r = (int)( 255.0f * brightness + 0.5f );
	g = (int)( 80.0f * brightness + 0.5f );
	b = 0;
}

int CHudDeathNotice :: Init( void )
{
	gHUD.AddHudElem( this );

	HOOK_MESSAGE( gHUD.m_DeathNotice, DeathMsg );

	hud_deathnotice_time = CVAR_CREATE( "hud_deathnotice_time", "6", FCVAR_ARCHIVE );
	hud_deathnotice_x = CVAR_CREATE( "hud_deathnotice_x", "4", FCVAR_ARCHIVE );
	hud_deathnotice_y = CVAR_CREATE( "hud_deathnotice_y", "0", FCVAR_ARCHIVE );
	hud_deathnotice_bg_alpha = CVAR_CREATE( "hud_deathnotice_bg_alpha", "96", FCVAR_ARCHIVE );
	hud_deathnotice_bg_softness = CVAR_CREATE( "hud_deathnotice_bg_softness", "100", FCVAR_ARCHIVE );
	hud_deathnotice_anim_time = CVAR_CREATE( "hud_deathnotice_anim_time", "0.18", FCVAR_ARCHIVE );
	hud_deathnotice_row_gap = CVAR_CREATE( "hud_deathnotice_row_gap", "24", FCVAR_ARCHIVE );
	cl_killfeed_smooth = CVAR_CREATE( "cl_killfeed_smooth", "1", FCVAR_ARCHIVE );
	cl_killsound = CVAR_CREATE( "cl_killsound", "0", FCVAR_ARCHIVE );
	cl_killsound_path = CVAR_CREATE( "cl_killsound_path", "buttons/bell1.wav", FCVAR_ARCHIVE );
	m_iFlags = 0;

	return 1;
}


void CHudDeathNotice :: InitHUDData( void )
{
	g_DeathNoticeList.clear();
}


int CHudDeathNotice :: VidInit( void )
{
	m_HUD_d_skull = gHUD.GetSpriteIndex( "d_skull" );
	m_HUD_d_headshot = gHUD.GetSpriteIndex("d_headshot");

	return 1;
}

int CHudDeathNotice :: Draw( float flTime )
{
	int i;
	const bool smooth = cl_killfeed_smooth && cl_killfeed_smooth->value > 0.0f;
	const float animTime = DeathNotice_ClampFloat( hud_deathnotice_anim_time ? hud_deathnotice_anim_time->value : 0.18f, 0.01f, 1.0f );
	const int bgAlpha = DeathNotice_ClampInt( hud_deathnotice_bg_alpha ? (int)( hud_deathnotice_bg_alpha->value + 0.5f ) : 96, 0, 255 );

	for( i = 0; i < (int)g_DeathNoticeList.size(); i++ )
	{
		DeathNoticeItem &notice = g_DeathNoticeList[i];

		if ( notice.flDisplayTime < flTime )
		{ // display time has expired
			// remove the current item from the list
			g_DeathNoticeList.erase( g_DeathNoticeList.begin() + i );
			i--;  // continue on the next item;  stop the counter getting incremented
			continue;
		}

		notice.flDisplayTime = min( notice.flDisplayTime, flTime + DEATHNOTICE_DISPLAY_TIME );

		if( notice.iSlot != i )
		{
			notice.iPrevSlot = notice.iSlot >= 0 ? notice.iSlot : i;
			notice.iSlot = i;
			notice.flSlotMoveTime = flTime;
		}

		// Hide when scoreboard drawing. It will break triapi
		//if ( gViewPort && gViewPort->AllowedToPrintText() )
		//if ( !gHUD.m_iNoConsolePrint )
		{
			const float enter = smooth ? DeathNotice_EaseOutCubic( ( flTime - notice.flCreateTime ) / animTime ) : 1.0f;
			const float exit = DeathNotice_ClampFloat( ( notice.flDisplayTime - flTime ) / animTime, 0.0f, 1.0f );
			const float fade = enter < exit ? enter : exit;
			const float slot = smooth ? DeathNotice_EaseOutCubic( ( flTime - notice.flSlotMoveTime ) / animTime ) : 1.0f;
			const float fromY = (float)DeathNotice_RowBaseY( notice.iPrevSlot, hud_deathnotice_row_gap, hud_deathnotice_y );
			const float toY = (float)DeathNotice_RowBaseY( i, hud_deathnotice_row_gap, hud_deathnotice_y );
			const int y = (int)( fromY + ( toY - fromY ) * slot + 0.5f );

			int id = ( notice.iId == -1 ) ? m_HUD_d_skull : notice.iId;
			const int weaponWidth = gHUD.GetSpriteRect(id).Width();
			const int headshotId = notice.iHeadShotId ? m_HUD_d_headshot : 0;
			const int headshotWidth = headshotId ? gHUD.GetSpriteRect(headshotId).Width() : 0;
			const int killerWidth = notice.bSuicide ? 0 : DrawUtils::ConsoleStringLen( notice.szKiller ) + 5;
			const int victimWidth = notice.bNonPlayerKill ? 0 : DrawUtils::ConsoleStringLen( notice.szVictim );
			const int rowWidth = killerWidth + weaponWidth + headshotWidth + victimWidth;
			const int slideX = smooth ? (int)( XRES( 24 ) * ( 1.0f - enter ) + 0.5f ) : 0;
			const int rightPad = DeathNotice_RightPad( hud_deathnotice_x );
			int x = ScreenWidth - rowWidth - rightPad + slideX;

			if( bgAlpha > 0 && fade > 0.0f )
			{
				const int rowHeight = DeathNotice_RectHeight( id, headshotId );
				DeathNotice_DrawSoftBackground(
					x - DEATHNOTICE_BG_PAD_X,
					y - DEATHNOTICE_BG_PAD_Y,
					rowWidth + DEATHNOTICE_BG_PAD_X * 2,
					rowHeight + DEATHNOTICE_BG_PAD_Y * 2,
					DeathNotice_ClampInt( (int)( bgAlpha * fade + 0.5f ), 0, 255 ),
					hud_deathnotice_bg_softness
				);
			}

			if ( !notice.bSuicide )
			{
				// Draw killers name
				DeathNotice_SetConsoleColor( notice.KillerColor, fade );
				x = 5 + DrawUtils::DrawConsoleString( x, y, notice.szKiller );
			}

			// Draw death weapon
			int r, g, b;
			DeathNotice_GetSpriteColor( notice, fade, r, g, b );
			SPR_Set( gHUD.GetSprite(id), r, g, b );
			SPR_DrawAdditive( 0, x, y, &gHUD.GetSpriteRect(id) );

			x += weaponWidth;

			if( headshotId )
			{
				SPR_Set( gHUD.GetSprite(headshotId), r, g, b );
				SPR_DrawAdditive( 0, x, y, &gHUD.GetSpriteRect(headshotId));
				x += headshotWidth;
			}

			// Draw victims name (if it was a player that was killed)
			if ( !notice.bNonPlayerKill )
			{
				DeathNotice_SetConsoleColor( notice.VictimColor, fade );
				x = DrawUtils::DrawConsoleString( x, y, notice.szVictim );
			}
		}
	}

	if( g_DeathNoticeList.empty() )
		m_iFlags &= ~HUD_DRAW; // disable hud item

	return 1;
}

// This message handler may be better off elsewhere
int CHudDeathNotice :: MsgFunc_DeathMsg( const char *pszName, int iSize, void *pbuf )
{
	m_iFlags |= HUD_DRAW;

	BufferReader reader( pszName, pbuf, iSize );

	int killer = reader.ReadByte();
	int victim = reader.ReadByte();
	int headshot = reader.ReadByte();

	char killedwith[32];
	strlcpy( killedwith, "d_", sizeof( killedwith ) );
	strlcat( killedwith, reader.ReadString(), sizeof( killedwith ) );

	//if (gViewPort)
	//	gViewPort->DeathMsg( killer, victim );
	gHUD.m_Scoreboard.DeathMsg( killer, victim );

	gHUD.m_Spectator.DeathMessage(victim);

	//if (gViewPort)
		//gViewPort->GetAllPlayersInfo();
	gHUD.m_Scoreboard.GetAllPlayersInfo();
	DeathNoticeItem notice = {};
	notice.flCreateTime = gHUD.m_flTime;
	notice.flSlotMoveTime = gHUD.m_flTime;
	notice.iSlot = (int)g_DeathNoticeList.size();
	notice.iPrevSlot = notice.iSlot;

	// Get the Killer's name
	const char *killer_name = NULL;
	bool killer_this_player = false;
	if ( killer >= 1 && killer <= MAX_PLAYERS )
	{
		killer_name = g_PlayerInfoList[killer].name;
		killer_this_player = g_PlayerInfoList[killer].thisplayer;
	}

	if ( !killer_name )
	{
		killer_name = "";
		notice.szKiller[0] = 0;
	}
	else
	{
		notice.KillerColor = GetClientColor( killer );
		strlcpy( notice.szKiller, killer_name, sizeof( notice.szKiller ) );
	}
	notice.bKillerIsLocal = killer_this_player;

	// Get the Victim's name
	const char *victim_name = NULL;

	if ( victim >= 1 && victim <= MAX_PLAYERS )
		victim_name = g_PlayerInfoList[ victim ].name;

	if ( !victim_name )
	{
		victim_name = "";
		notice.szVictim[0] = 0;
	}
	else
	{
		notice.VictimColor = GetClientColor( victim );
		strlcpy( notice.szVictim, victim_name, sizeof( notice.szVictim ) );
	}

	// Is it a non-player object kill?
	// If victim is 255, the killer killed a specific, non-player object (like a sentrygun)
	if( victim == 255 )
	{
		notice.bNonPlayerKill = true;

		// Store the object's name in the Victim slot (skip the d_ bit)
		strlcpy( notice.szVictim, killedwith+2, sizeof( notice.szVictim ) );
	}
	else
	{
		if ( killer == victim || killer == 0 )
			notice.bSuicide = true;

		if ( !strncmp( killedwith, "d_teammate", sizeof(killedwith)  ) )
			notice.bTeamKill = true;
	}

	notice.iHeadShotId = headshot;

	// Find the sprite in the list
	int spr = gHUD.GetSpriteIndex( killedwith );

	notice.iId = spr;

	notice.flDisplayTime = gHUD.m_flTime + hud_deathnotice_time->value;

	// Play kill sound
	if ((killer_this_player || g_iUser2 == killer) &&
		!notice.bNonPlayerKill &&
		!notice.bSuicide &&
		cl_killsound->value > 0.0f)
	{
		PlaySound(cl_killsound_path->string, cl_killsound->value);
	}

	if (notice.bNonPlayerKill)
	{
		ConsolePrint( notice.szKiller );
		ConsolePrint( " killed a " );
		ConsolePrint( notice.szVictim );
		ConsolePrint( "\n" );
	}
	else
	{
		// record the death notice in the console
		if ( notice.bSuicide )
		{
			ConsolePrint( notice.szVictim );

			if ( !strncmp( killedwith, "d_world", sizeof(killedwith)  ) )
			{
				ConsolePrint( " died" );
			}
			else
			{
				ConsolePrint( " killed self" );
			}
		}
		else if ( notice.bTeamKill )
		{
			ConsolePrint( notice.szKiller );
			ConsolePrint( " killed his teammate " );
			ConsolePrint( notice.szVictim );
		}
		else
		{
			if( headshot )
				ConsolePrint( "*** ");
			ConsolePrint( notice.szKiller );
			ConsolePrint( " killed " );
			ConsolePrint( notice.szVictim );
		}

		if ( *killedwith && (*killedwith > 13 ) && strncmp( killedwith, "d_world", sizeof(killedwith) ) && !notice.bTeamKill )
		{
			if ( headshot )
				ConsolePrint(" with a headshot from ");
			else
				ConsolePrint(" with ");

			ConsolePrint( killedwith+2 ); // skip over the "d_" part
		}

		if( headshot ) ConsolePrint( " ***");
		ConsolePrint( "\n" );
	}

	g_DeathNoticeList.push_back( notice );

	return 1;
}
