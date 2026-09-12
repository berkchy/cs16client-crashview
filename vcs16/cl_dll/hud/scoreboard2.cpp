/***
*
*	Copyright (c) 1999, Valve LLC. All rights reserved.
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
// Scoreboard.cpp
//
// implementation of CHudScoreboard2 class
//
#include <string.h>
#include <stdio.h>
#include <ctype.h>
#include <math.h>
#include "hud.h"
#include "cl_util.h"
#include "parsemsg.h"
#include "triangleapi.h"
#include "com_weapons.h"
#include "cdll_dll.h"
#include "draw_util.h"
#include "vgui_parser.h"
#include "eventscripts.h"

extern hud_player_info_t   g_PlayerInfoList[MAX_PLAYERS+1];
extern extra_player_info_t	g_PlayerExtraInfo[MAX_PLAYERS+1];
extern team_info_t         g_TeamInfo[MAX_TEAMS+1];
extern hostage_info_t      g_HostageInfo[MAX_HOSTAGES+1];
extern int g_iUser1;
extern int g_iUser2;
extern int g_iUser3;
extern int g_iTeamNumber;

extern int xstart, xend;
extern int ystart, yend;

enum
{
	COL_NAME = 0,
	COL_ATTRIB,
	COL_HP,
	COL_MONEY,
	COL_KILLS,
	COL_DEATHS,
	COL_PING,
	TOTAL_COLUMNS
};

static struct Column
{
	int start, end;
	const char *name;

	Column() :
	    start( 0 ), end( 0 ), name( nullptr ) { }

	Column( int s, const char *n = nullptr, bool reverse = true )
	{
		name = n;
		end = 0;
		start = 0;

		if ( reverse )
		{
			start = s;
			if ( n )
				end = start - DrawUtils::HudStringLen( n );
		}
		else
		{
			start = s;
			if ( n )
				end = start + DrawUtils::HudStringLen( n );
		}
	}
} g_Columns[TOTAL_COLUMNS];

static int Scoreboard_ClampInt( int value, int minValue, int maxValue )
{
	if ( value < minValue )
		return minValue;
	if ( value > maxValue )
		return maxValue;
	return value;
}

static int Scoreboard_CvarAlpha( cvar_t *cvar, int fallback )
{
	if ( !cvar )
		return fallback;

	return Scoreboard_ClampInt( (int)cvar->value, 0, 255 );
}

static void Scoreboard_DrawBorder( int x, int y, int wide, int tall, int r, int g, int b, int a )
{
	FillRGBA( x + 1,        y,            wide - 1, 1,        r, g, b, a );
	FillRGBA( x,            y,            1,        tall - 1, r, g, b, a );
	FillRGBA( x + wide - 1, y + 1,        1,        tall - 1, r, g, b, a );
	FillRGBA( x,            y + tall - 1, wide - 1, 1,        r, g, b, a );
}

static void Scoreboard_DrawTextShadow( int x, int y, int maxX, const char *text, int r, int g, int b )
{
	DrawUtils::DrawHudString( x + 1, y + 1, maxX, text, 0, 0, 0 );
	DrawUtils::DrawHudString( x, y, maxX, text, r, g, b );
}

static void Scoreboard_DrawReverseTextShadow( int x, int y, int minX, const char *text, int r, int g, int b )
{
	DrawUtils::DrawHudStringReverse( x + 1, y + 1, minX + 1, text, 0, 0, 0 );
	DrawUtils::DrawHudStringReverse( x, y, minX, text, r, g, b );
}

static void Scoreboard_DrawCenteredText( int x, int y, int wide, const char *text, int r, int g, int b )
{
	int textWide = DrawUtils::HudStringLen( text );
	Scoreboard_DrawTextShadow( x + ( wide - textWide ) / 2, y, x + wide, text, r, g, b );
}

static void Scoreboard_DrawCenteredScaledText( int x, int y, int wide, const char *text, int r, int g, int b, float scale )
{
	int textWide = (int)( DrawUtils::HudStringLen( text ) * scale + 0.5f );
	int drawX = x + ( wide - textWide ) / 2;
	DrawUtils::DrawHudString( drawX + 1, y + 1, x + wide, text, 0, 0, 0, scale );
	DrawUtils::DrawHudString( drawX, y, x + wide, text, r, g, b, scale );
}

static void Scoreboard_DrawRoundedPanel( int x, int y, int wide, int tall, int rad, int r, int g, int b, int a )
{
	if ( wide <= 0 || tall <= 0 )
		return;

	if ( rad < 1 )
	{
		FillRGBABlend( x, y, wide, tall, r, g, b, a );
		return;
	}

	rad = min( rad, wide / 2 );
	rad = min( rad, tall / 2 );

	for ( int dy = 0; dy < rad; dy++ )
	{
		int dist = rad - dy;
		int halfW = (int)( sqrtf( (float)rad * rad - (float)dist * dist ) + 0.5f );
		int skip = rad - halfW;

		if ( wide - skip * 2 > 0 )
		{
			FillRGBABlend( x + skip, y + dy, wide - skip * 2, 1, r, g, b, a );
			FillRGBABlend( x + skip, y + tall - 1 - dy, wide - skip * 2, 1, r, g, b, a );
		}
	}

	if ( tall - rad * 2 > 0 )
		FillRGBABlend( x, y + rad, wide, tall - rad * 2, r, g, b, a );
}

static void Scoreboard_DrawRoundedBorder( int x, int y, int wide, int tall, int rad, int r, int g, int b, int a )
{
	if ( rad < 1 )
	{
		FillRGBABlend( x, y, wide, tall, r, g, b, a );
		return;
	}

	rad = min( rad, wide / 2 );
	rad = min( rad, tall / 2 );

	for ( int dy = 0; dy < rad; dy++ )
	{
		int dist = rad - dy;
		int halfW = (int)( sqrtf( (float)rad * rad - (float)dist * dist ) + 0.5f );
		int skip = rad - halfW;

		FillRGBABlend( x + skip, y + dy, 1, 1, r, g, b, a );
		FillRGBABlend( x + wide - 1 - skip, y + dy, 1, 1, r, g, b, a );
		FillRGBABlend( x + skip, y + tall - 1 - dy, 1, 1, r, g, b, a );
		FillRGBABlend( x + wide - 1 - skip, y + tall - 1 - dy, 1, 1, r, g, b, a );
	}

	if ( wide - rad * 2 > 0 )
	{
		FillRGBABlend( x + rad, y, wide - rad * 2, 1, r, g, b, a );
		FillRGBABlend( x + rad, y + tall - 1, wide - rad * 2, 1, r, g, b, a );
	}

	if ( tall - rad * 2 > 0 )
	{
		FillRGBABlend( x, y + rad, 1, tall - rad * 2, r, g, b, a );
		FillRGBABlend( x + wide - 1, y + rad, 1, tall - rad * 2, r, g, b, a );
	}
}

struct scoreboard_team_summary_t
{
	int players;
	int frags;
	int deaths;
	int ping;
};

static team_info_t *Scoreboard_FindTeamInfo( int teamnumber )
{
	for ( int i = 1; i <= gHUD.m_Scoreboard.m_iNumTeams; i++ )
	{
		if ( g_TeamInfo[i].teamnumber == teamnumber )
			return &g_TeamInfo[i];
	}

	return NULL;
}

static void Scoreboard_AddPlayerToSummary( scoreboard_team_summary_t &summary, int player )
{
	summary.players++;
	summary.frags += g_PlayerExtraInfo[player].frags;
	summary.deaths += g_PlayerExtraInfo[player].deaths;
	summary.ping += g_PlayerInfoList[player].ping;
}

static bool Scoreboard_PlayerMatchesTeam( int player, int teamnumber )
{
	int playerTeam = g_PlayerExtraInfo[player].teamnumber;

	if ( teamnumber == TEAM_SPECTATOR )
		return playerTeam == TEAM_SPECTATOR || playerTeam == TEAM_UNASSIGNED || !stricmp( g_PlayerExtraInfo[player].teamname, "SPECTATOR" );

	return playerTeam == teamnumber;
}

//#include "vgui_TeamFortressViewport.h"

static void __ShowScores2( void ) { gHUD.m_Scoreboard2.UserCmd_ShowScores(); }
static void __HideScores2( void ) { gHUD.m_Scoreboard2.UserCmd_HideScores(); }

int CHudScoreboard2 :: Init( void )
{
	gHUD.AddHudElem( this );

	gEngfuncs.pfnAddCommand( "+showscores2", __ShowScores2 );
	gEngfuncs.pfnAddCommand( "-showscores2", __HideScores2 );

	HOOK_MESSAGE( gHUD.m_Scoreboard, ScoreInfo );
	HOOK_MESSAGE( gHUD.m_Scoreboard, TeamScore );
	HOOK_MESSAGE( gHUD.m_Scoreboard, TeamInfo );

	InitHUDData();

	cl_showpacketloss = CVAR_CREATE( "cl_showpacketloss", "0", FCVAR_ARCHIVE );
	cl_showplayerversion = CVAR_CREATE( "cl_showplayerversion", "0", 0 );
	cl_show_scoreboard_on_death = CVAR_CREATE( "cl_show_scoreboard_on_death", "0", FCVAR_ARCHIVE );
	m_pScoreboardBgAlpha = CVAR_CREATE( "hud_scoreboard_bg_alpha", "176", FCVAR_ARCHIVE );
	m_pScoreboardRowAlpha = CVAR_CREATE( "hud_scoreboard_row_alpha", "34", FCVAR_ARCHIVE );
	cl_scoreboard_anim = CVAR_CREATE( "cl_scoreboard_anim", "1", FCVAR_ARCHIVE );

	return 1;
}


int CHudScoreboard2 :: VidInit( void )
{
	xstart = ScreenWidth * 0.125f;
	xend = ScreenWidth - xstart;
	ystart = 100;
	yend = ScreenHeight - ystart;
	m_bForceDraw = false;
	m_flAnimProgress = 0.0f;
	m_flAnimProgressDisplay = 0.0f;
	m_iAnimDir = 0;
	m_HUD_d_skull = gHUD.GetSpriteIndex( "d_skull" );

	// Load sprites here
	return 1;
}

void CHudScoreboard2 :: InitHUDData( void )
{
	memset( g_PlayerExtraInfo, 0, sizeof g_PlayerExtraInfo );
	m_iLastKilledBy = 0;
	m_fLastKillTime = 0;
	m_iPlayerNum = 0;
	m_iNumTeams = 0;
	memset( g_TeamInfo, 0, sizeof g_TeamInfo );

	for ( int i = 1; i <= MAX_PLAYERS; i++ )
	{
		// a1ba: get the cl.playernum from the engine
		// it shouldn't ever change during normal gameplay
		if( !m_iPlayerNum && EV_IsLocal( i ))
			m_iPlayerNum = i;

		g_PlayerExtraInfo[i].sb_health = -1;
		g_PlayerExtraInfo[i].sb_account = -1;
	}

	m_iFlags &= ~HUD_DRAW;  // starts out inactive

	m_flAnimProgress = 0.0f;
	m_flAnimProgressDisplay = 0.0f;
	m_iAnimDir = 0;

	m_iFlags |= HUD_INTERMISSION; // is always drawn during an intermission
}

bool CHudScoreboard2 :: ShouldDrawScoreboard() const
{
	if( m_bForceDraw )
		return true;

	if( m_bShowscoresHeld || gHUD.m_iIntermission )
		return true;

	if( cl_show_scoreboard_on_death && cl_show_scoreboard_on_death->value && gHUD.m_Health.m_iHealth <= 0 )
		return true;

	return false;
}

// Y positions
#define ROW_GAP  15

int CHudScoreboard2 :: Draw( float flTime )
{
	const bool shouldDraw = ShouldDrawScoreboard();

	if ( !shouldDraw )
	{
		if ( m_iAnimDir >= 0 && m_flAnimProgress == 0.0f )
			return 1;                            // closed and idle
		m_iAnimDir = -1;                         // fade out
	}
	else if ( m_iAnimDir < 0 || m_flAnimProgress == 0.0f )
	{
		m_iAnimDir = 1;                          // fade in / re-open
	}

	if ( cl_scoreboard_anim && cl_scoreboard_anim->value == 0.0f )
	{
		// animation disabled: snap instantly
		m_iAnimDir = 0;
		m_flAnimProgress = shouldDraw ? 1.0f : 0.0f;
		m_flAnimProgressDisplay = m_flAnimProgress;
	}
	else
	{
float flSpeed = 4.0f;
	if ( cl_scoreboard_anim && cl_scoreboard_anim->value > 0.0f )
		flSpeed = 4.0f * cl_scoreboard_anim->value;

		m_flAnimProgress += m_iAnimDir * flTime * flSpeed;
		if ( m_flAnimProgress < 0.0f )
			m_flAnimProgress = 0.0f;
		else if ( m_flAnimProgress > 1.0f )
			m_flAnimProgress = 1.0f;

		// cubic ease-out for a smoother reveal
		float t = m_flAnimProgress;
		m_flAnimProgressDisplay = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
	}

	if ( m_flAnimProgress == 0.0f )
	{
		m_iAnimDir = 0;
		if ( !shouldDraw )
			return 1;
	}
	else if ( m_flAnimProgress >= 1.0f )
	{
		m_iAnimDir = 0;
		m_flAnimProgress = 1.0f;
	}

	if( !m_bForceDraw )
	{
		xstart     = 0.09f * ScreenWidth;
		xend       = ScreenWidth - xstart;
		ystart     = max( 64, (int)( 0.14f * ScreenHeight ) );
		yend       = ScreenHeight - max( 52, (int)( 0.10f * ScreenHeight ) );
		m_colors.r = 0;
		m_colors.g = 0;
		m_colors.b = 0;
		m_colors.a = Scoreboard_CvarAlpha( m_pScoreboardBgAlpha, 176 );
		m_bDrawStroke = true;
	}

	return DrawScoreboard(flTime);
}

int CHudScoreboard2 :: DrawScoreboard( float fTime )
{
	GetAllPlayersInfo();
	return DrawModernTeamScoreboard( fTime );
}

int CHudScoreboard2 :: DrawModernTeamPlayers( int teamnumber, int x, int y, int wide, int tall, int nameoffset )
{
	bool drawn[MAX_PLAYERS + 1];
	memset( drawn, 0, sizeof( drawn ) );

	// HUD glyphs are fixed-size, so the numeric columns use fixed pixel offsets
	// (right->left) instead of XRES-scaled ones: on high-res screens scaled
	// offsets pushed the columns apart and crushed the name area to nothing.
	// HP/$ sit closer to the block's middle; K/D stay right of them with a
	// generous gap so a 5-digit "$16000" never collides with K.
	const int pad = max( XRES( 8 ), 8 );
	const int rowAlpha = (int)( (float)Scoreboard_CvarAlpha( m_pScoreboardRowAlpha, 34 ) * m_flAnimProgressDisplay );
	const int rowTop = y + YRES( 24 );
	const int rowBottom = y + tall - YRES( 8 );
	const int pingX  = x + wide - pad;
	const int deathX = pingX - 34;
	const int killX  = deathX - 28;
	const int moneyX = killX - 68;
	const int hpX    = moneyX - 34;
	const int attrX  = hpX - 62;
	const int nameMaxX = attrX - 6;
	int row = 0;

	if ( teamnumber != TEAM_SPECTATOR )
	{
		Scoreboard_DrawTextShadow( x + pad + nameoffset, y + YRES( 8 ), x + wide, "Name", 190, 190, 190 );
		Scoreboard_DrawReverseTextShadow( hpX, y + YRES( 8 ), x, "HP", 190, 190, 190 );
		Scoreboard_DrawReverseTextShadow( moneyX, y + YRES( 8 ), x, "$", 190, 190, 190 );
		Scoreboard_DrawReverseTextShadow( killX, y + YRES( 8 ), x, "K", 190, 190, 190 );
		Scoreboard_DrawReverseTextShadow( deathX, y + YRES( 8 ), x, "D", 190, 190, 190 );
		Scoreboard_DrawReverseTextShadow( x + wide - pad, y + YRES( 8 ), x, "Ping", 190, 190, 190 );
	}
	else
	{
		Scoreboard_DrawTextShadow( x + pad + nameoffset, y + YRES( 8 ), x + wide, "Name", 190, 190, 190 );
		Scoreboard_DrawReverseTextShadow( x + wide - pad, y + YRES( 8 ), x, "Ping", 190, 190, 190 );
	}

	while ( rowTop + row * ROW_GAP + ROW_GAP <= rowBottom )
	{
		int bestPlayer = 0;
		int highestFrags = -99999;
		int lowestDeaths = 99999;

		for ( int i = 1; i <= MAX_PLAYERS; i++ )
		{
			if ( drawn[i] || !g_PlayerInfoList[i].name || !g_PlayerInfoList[i].name[0] )
				continue;

			if ( !Scoreboard_PlayerMatchesTeam( i, teamnumber ) )
				continue;

			if ( g_PlayerExtraInfo[i].frags > highestFrags ||
				( g_PlayerExtraInfo[i].frags == highestFrags && g_PlayerExtraInfo[i].deaths < lowestDeaths ) )
			{
				bestPlayer = i;
				highestFrags = g_PlayerExtraInfo[i].frags;
				lowestDeaths = g_PlayerExtraInfo[i].deaths;
			}
		}

		if ( !bestPlayer )
			break;

		drawn[bestPlayer] = true;

		int ypos = rowTop + row * ROW_GAP;
		if ( row % 2 == 0 )
			FillRGBABlend( x + pad / 2, ypos - 2, wide - pad, ROW_GAP + 2, 255, 255, 255, rowAlpha );
		else
			FillRGBABlend( x + pad / 2, ypos - 2, wide - pad, ROW_GAP + 2, 0, 0, 0, rowAlpha / 2 );

		if ( g_PlayerInfoList[bestPlayer].thisplayer )
			FillRGBABlend( x + pad / 2, ypos - 2, wide - pad, ROW_GAP + 2, 255, 180, 32, (int)( 54.0f * m_flAnimProgressDisplay ) );

		int r = 255, g = 255, b = 255;
		float *colors = GetClientColor( bestPlayer );
		r *= colors[0];
		g *= colors[1];
		b *= colors[2];

		Scoreboard_DrawTextShadow( x + pad + nameoffset, ypos, nameMaxX, g_PlayerInfoList[bestPlayer].name, r, g, b );

		if ( teamnumber != TEAM_SPECTATOR )
		{
			if( cl_showplayerversion && cl_showplayerversion->value != 0.0f )
			{
				Scoreboard_DrawReverseTextShadow( attrX, ypos, attrX - 64, gEngfuncs.PlayerInfo_ValueForKey( bestPlayer, "cscl_ver" ), r, g, b );
			}
			else
			{
				if( g_PlayerExtraInfo[bestPlayer].dead )
				{
					if( m_HUD_d_skull >= 0 )
					{
						wrect_t &rect = gHUD.GetSpriteRect( m_HUD_d_skull );
						int iconW = rect.Width();
						int iconH = rect.Height();
						SPR_Set( gHUD.GetSprite( m_HUD_d_skull ), r, g, b );
						SPR_DrawAdditive( 0, attrX - iconW, ypos + ( ROW_GAP - iconH ) / 2, &rect );
					}
					else
						Scoreboard_DrawReverseTextShadow( attrX, ypos, attrX - 64, Localize( "#Cstrike_DEAD" ), r, g, b );
				}
				else if( g_PlayerExtraInfo[bestPlayer].has_c4 )
					Scoreboard_DrawReverseTextShadow( attrX, ypos, attrX - 64, Localize( "#Cstrike_BOMB" ), r, g, b );
				else if( g_PlayerExtraInfo[bestPlayer].vip )
					Scoreboard_DrawReverseTextShadow( attrX, ypos, attrX - 64, Localize( "#Cstrike_VIP" ), r, g, b );
				else if( g_PlayerExtraInfo[bestPlayer].has_defuse_kit )
					Scoreboard_DrawReverseTextShadow( attrX, ypos, attrX - 64, Localize( "#Cstrike_DEFUSE_KIT" ), r, g, b );
			}

			if ( g_PlayerExtraInfo[bestPlayer].sb_health >= 0 && !g_PlayerExtraInfo[bestPlayer].dead )
			{
				if ( gHUD.m_pShowHealth && gHUD.m_pShowHealth->value )
				{
					static char buf[64];
					sprintf( buf, "%d", g_PlayerExtraInfo[bestPlayer].sb_health );
					Scoreboard_DrawReverseTextShadow( hpX, ypos, hpX - 40, buf, r, g, b );
				}
			}

			if ( g_PlayerExtraInfo[bestPlayer].sb_account >= 0 )
			{
				if ( gHUD.m_pShowMoney && gHUD.m_pShowMoney->value )
				{
					static char buf[64];
					sprintf( buf, "$%d", g_PlayerExtraInfo[bestPlayer].sb_account );
					Scoreboard_DrawReverseTextShadow( moneyX, ypos, moneyX - 72, buf, r, g, b );
				}
			}

			DrawUtils::DrawHudNumberString( killX, ypos, x, g_PlayerExtraInfo[bestPlayer].frags, r, g, b );
			DrawUtils::DrawHudNumberString( deathX, ypos, x, g_PlayerExtraInfo[bestPlayer].deaths, r, g, b );
		}

		static char pingBuf[16];
		const char *value;
		if( g_PlayerInfoList[bestPlayer].ping <= 5 &&
			( value = gEngfuncs.PlayerInfo_ValueForKey( bestPlayer, "*bot" ) ) &&
			atoi( value ) > 0 )
		{
			Scoreboard_DrawReverseTextShadow( x + wide - pad, ypos, deathX - 4, "BOT", r, g, b );
		}
		else
		{
			sprintf( pingBuf, "%d", g_PlayerInfoList[bestPlayer].ping );
			Scoreboard_DrawReverseTextShadow( x + wide - pad, ypos, deathX - 4, pingBuf, r, g, b );
		}

		row++;
	}

	return 1;
}

int CHudScoreboard2 :: DrawModernTeamScoreboard( float flTime )
{
	(void)flTime;

	scoreboard_team_summary_t ct = {};
	scoreboard_team_summary_t tr = {};
	scoreboard_team_summary_t spec = {};

	for ( int i = 1; i <= MAX_PLAYERS; i++ )
	{
		if ( !g_PlayerInfoList[i].name || !g_PlayerInfoList[i].name[0] )
			continue;

		if ( Scoreboard_PlayerMatchesTeam( i, TEAM_CT ) )
			Scoreboard_AddPlayerToSummary( ct, i );
		else if ( Scoreboard_PlayerMatchesTeam( i, TEAM_TERRORIST ) )
			Scoreboard_AddPlayerToSummary( tr, i );
		else if ( Scoreboard_PlayerMatchesTeam( i, TEAM_SPECTATOR ) )
			Scoreboard_AddPlayerToSummary( spec, i );
	}

	team_info_t *ctInfo = Scoreboard_FindTeamInfo( TEAM_CT );
	team_info_t *trInfo = Scoreboard_FindTeamInfo( TEAM_TERRORIST );
	if ( ctInfo && ctInfo->scores_overriden )
		ct.frags = ctInfo->frags;
	if ( trInfo && trInfo->scores_overriden )
		tr.frags = trInfo->frags;

	char serverName[90];
	if ( gHUD.m_szServerName[0] )
		strncpy( serverName, gHUD.m_szServerName, sizeof( serverName ) );
	else
		strncpy( serverName, "Counter-Strike", sizeof( serverName ) );
	serverName[sizeof( serverName ) - 1] = 0;

	const int boardX = xstart;
	const int boardY = ystart - (int)( ( 1.0f - m_flAnimProgressDisplay ) * YRES( 60 ) );
	const int boardW = xend - xstart;
	const int boardH = yend - ystart;
	const int pad = max( XRES( 12 ), 12 );
	const int gap = max( XRES( 8 ), 8 );
	const int headerH = max( YRES( 78 ), 72 );
	const int specH = max( YRES( 78 ), 70 );
	const int teamY = boardY + headerH + gap;
	const int teamH = max( ROW_GAP * 5, boardH - headerH - specH - gap * 2 );
	const int specY = teamY + teamH + gap;
	const int colW = ( boardW - pad * 2 - gap ) / 2;
	const int leftX = boardX + pad;
	const int rightX = leftX + colW + gap;
	const int bgAlpha = (int)( (float)Scoreboard_CvarAlpha( m_pScoreboardBgAlpha, 176 ) * m_flAnimProgressDisplay );
	const int roundR = max( XRES( 8 ), 8 );

	int ctR, ctG, ctB;
	int trR, trG, trB;
	GetTeamColor( ctR, ctG, ctB, TEAM_CT );
	GetTeamColor( trR, trG, trB, TEAM_TERRORIST );

	Scoreboard_DrawRoundedPanel( boardX, boardY, boardW, boardH, roundR, 0, 0, 0, bgAlpha );

	if( m_bDrawStroke )
	{
		Scoreboard_DrawRoundedBorder( boardX, boardY, boardW, boardH, roundR, 255, 180, 32, (int)( 220.0f * m_flAnimProgressDisplay ) );
		Scoreboard_DrawRoundedBorder( boardX + 3, boardY + 3, boardW - 6, boardH - 6, roundR, 255, 180, 32, (int)( 60.0f * m_flAnimProgressDisplay ) );
	}

	Scoreboard_DrawCenteredText( boardX + pad, boardY + YRES( 10 ), boardW - pad * 2, serverName, 255, 180, 32 );

	const int halfW = ( boardW - pad * 2 ) / 2;
	const int scoreTop = boardY + YRES( 30 );
	Scoreboard_DrawCenteredText( boardX + pad, scoreTop, halfW, "CT", ctR, ctG, ctB );
	Scoreboard_DrawCenteredText( boardX + pad + halfW, scoreTop, halfW, "TR", trR, trG, trB );
	Scoreboard_DrawCenteredText( boardX + pad + halfW - XRES( 8 ), scoreTop, XRES( 16 ), "|", 255, 180, 32 );

	char scoreBuf[16];
	sprintf( scoreBuf, "%d", ct.frags );
	Scoreboard_DrawCenteredScaledText( boardX + pad, boardY + YRES( 47 ), halfW, scoreBuf, ctR, ctG, ctB, 1.85f );
	sprintf( scoreBuf, "%d", tr.frags );
	Scoreboard_DrawCenteredScaledText( boardX + pad + halfW, boardY + YRES( 47 ), halfW, scoreBuf, trR, trG, trB, 1.85f );

	char title[64];
	sprintf( title, "Counter-Terrorists  (%d)", ct.players );
	Scoreboard_DrawCenteredText( leftX, teamY + YRES( 6 ), colW, title, ctR, ctG, ctB );
	sprintf( title, "Terrorists  (%d)", tr.players );
	Scoreboard_DrawCenteredText( rightX, teamY + YRES( 6 ), colW, title, trR, trG, trB );

	DrawModernTeamPlayers( TEAM_CT, leftX, teamY + YRES( 18 ), colW, teamH - YRES( 18 ), 0 );
	DrawModernTeamPlayers( TEAM_TERRORIST, rightX, teamY + YRES( 18 ), colW, teamH - YRES( 18 ), 0 );

	sprintf( title, "Spectators  (%d)", spec.players );
	Scoreboard_DrawCenteredText( leftX, specY + YRES( 6 ), boardW - pad * 2, title, 255, 180, 32 );
	DrawModernTeamPlayers( TEAM_SPECTATOR, leftX, specY + YRES( 18 ), boardW - pad * 2, specH - YRES( 18 ), 0 );

	return 1;
}

int CHudScoreboard2 :: DrawTeams( float list_slot )
{
	int j;
	int ypos = ystart + (list_slot * ROW_GAP) + 5;

	// clear out team scores
	for ( int i = 1; i <= m_iNumTeams; i++ )
	{
		if ( !g_TeamInfo[i].scores_overriden )
			g_TeamInfo[i].frags = g_TeamInfo[i].deaths = 0;
		g_TeamInfo[i].sumping = 0;
		g_TeamInfo[i].players = 0;
		g_TeamInfo[i].already_drawn = FALSE;
	}

	// recalc the team scores, then draw them
	for ( int i = 1; i < MAX_PLAYERS; i++ )
	{
		if ( !g_PlayerInfoList[i].name || !g_PlayerInfoList[i].name[0] )
			continue; // empty player slot, skip

		if ( g_PlayerExtraInfo[i].teamname[0] == 0 )
			continue; // skip over players who are not in a team

		// find what team this player is in
		for ( j = 1; j <= m_iNumTeams; j++ )
		{
			if ( !stricmp( g_PlayerExtraInfo[i].teamname, g_TeamInfo[j].name ) )
				break;
		}

		if ( j > m_iNumTeams )  // player is not in a team, skip to the next guy
			continue;

		if ( !g_TeamInfo[j].scores_overriden )
		{
			g_TeamInfo[j].frags += g_PlayerExtraInfo[i].frags;
			g_TeamInfo[j].deaths += g_PlayerExtraInfo[i].deaths;
		}

		g_TeamInfo[j].sumping += g_PlayerInfoList[i].ping;

		if ( g_PlayerInfoList[i].thisplayer )
			g_TeamInfo[j].ownteam = TRUE;
		else
			g_TeamInfo[j].ownteam = FALSE;

		g_TeamInfo[j].players++;
	}

	// Draw the teams
	int iSpectatorPos = -1;

	while( true )
	{
		int highest_frags = -99999; int lowest_deaths = 99999;
		int best_team = 0;

		for ( int i = 1; i <= m_iNumTeams; i++ )
		{
			// don't draw team without players
			if ( g_TeamInfo[i].players <= 0 )
				continue;

			if (!strnicmp(g_TeamInfo[i].name, "SPECTATOR", MAX_TEAM_NAME))
			{
				iSpectatorPos = i;
				continue;
			}

			if ( !g_TeamInfo[i].already_drawn && g_TeamInfo[i].frags >= highest_frags )
			{
				if ( g_TeamInfo[i].frags > highest_frags || g_TeamInfo[i].deaths < lowest_deaths )
				{
					best_team = i;
					lowest_deaths = g_TeamInfo[i].deaths;
					highest_frags = g_TeamInfo[i].frags;
				}
			}
		}

		// draw the best team on the scoreboard
		if ( !best_team )
		{
			// if spectators is found and still not drawn
			if( iSpectatorPos != -1 && g_TeamInfo[iSpectatorPos].already_drawn == FALSE )
				best_team = iSpectatorPos;
			else break;
		}
		// draw out the best team
		team_info_t *team_info = &g_TeamInfo[best_team];

		// don't draw team without players
		if ( team_info->players <= 0 )
			continue;

		ypos = ystart + (list_slot * ROW_GAP);

		// check we haven't drawn too far down
		if ( ypos > yend )  // don't draw to close to the lower border
			break;

		int r, g, b;
		char teamName[64];

		char numPlayers[16];
		sprintf( numPlayers, "%d", team_info->players );

		char fmtString[32];
		const char *fmtStringName = team_info->players == 1 ? "#Cstrike_ScoreBoard_Player" : "#Cstrike_ScoreBoard_Players";
		strncpy( fmtString, Localize( fmtStringName ), sizeof( fmtString ) );
		fmtString[sizeof( fmtString ) - 1] = 0;

		if ( !strcmp( fmtString, fmtStringName ) )
		{
			const char *fallback = team_info->players == 1 ? "%s1    -   %s2 player" : "%s1    -   %s2 players";
			strncpy( fmtString, fallback, sizeof( fmtString ) );
			fmtString[sizeof( fmtString ) - 1] = 0;
		}

		GetTeamColor( r, g, b, team_info->teamnumber );
		switch ( team_info->teamnumber )
		{
		case TEAM_TERRORIST:
		{
			const char *args[2] = { Localize( "#Cstrike_ScoreBoard_Ter" ), numPlayers };
			Localize_Format( teamName, sizeof( teamName ), fmtString, args, 2 );
			DrawUtils::DrawHudNumberString( g_Columns[COL_KILLS].start, ypos, g_Columns[COL_KILLS].end, team_info->frags, r, g, b );
			break;
		}
		case TEAM_CT:
		{
			const char *args[2] = { Localize( "#Cstrike_ScoreBoard_CT" ), numPlayers };
			Localize_Format( teamName, sizeof( teamName ), fmtString, args, 2 );
			DrawUtils::DrawHudNumberString( g_Columns[COL_KILLS].start, ypos, g_Columns[COL_KILLS].end, team_info->frags, r, g, b );
			break;
		}
		case TEAM_SPECTATOR:
		case TEAM_UNASSIGNED:
			strncpy( teamName, Localize( "#Spectators" ), sizeof( teamName ) );
			break;
		}

		DrawUtils::DrawHudString( g_Columns[COL_NAME].start, ypos, g_Columns[COL_NAME].end, teamName, r, g, b );
		DrawUtils::DrawHudNumberString( g_Columns[COL_PING].start, ypos, g_Columns[COL_PING].end, team_info->sumping / team_info->players, r, g, b );

		team_info->already_drawn = TRUE;  // set the already_drawn to be TRUE, so this team won't get drawn again

		// draw underline
		list_slot += 1.2f;
		FillRGBA( xstart, ystart + (list_slot * ROW_GAP), xend - xstart, 1, r, g, b, 255);

		list_slot += 0.4f;
		// draw all the players that belong to this team, indented slightly
		list_slot = DrawPlayers( list_slot, 10, team_info->name );
	}

	// draw all the players who are not in a team
	list_slot += 4.0f;
	DrawPlayers( list_slot, 0, "" );

	return 1;
}

// returns the ypos where it finishes drawing
int CHudScoreboard2 :: DrawPlayers( float list_slot, int nameoffset, const char *team )
{
	// draw the players, in order,  and restricted to team if set
	while ( 1 )
	{
		// Find the top ranking player
		int highest_frags = -99999;	int lowest_deaths = 99999;
		int best_player = 0;

		for ( int i = 1; i < MAX_PLAYERS; i++ )
		{
			if ( g_PlayerInfoList[i].name && g_PlayerExtraInfo[i].frags >= highest_frags )
			{
				if ( !(team && stricmp(g_PlayerExtraInfo[i].teamname, team)) )  // make sure it is the specified team
				{
					extra_player_info_t *pl_info = &g_PlayerExtraInfo[i];
					if ( pl_info->frags > highest_frags || pl_info->deaths < lowest_deaths )
					{
						best_player = i;
						lowest_deaths = pl_info->deaths;
						highest_frags = pl_info->frags;
					}
				}
			}
		}

		if ( !best_player )
			break;

		// draw out the best player
		hud_player_info_t *pl_info = &g_PlayerInfoList[best_player];

		int ypos = ystart + (list_slot * ROW_GAP);

		// check we haven't drawn too far down
		if ( ypos > yend )  // don't draw to close to the lower border
			break;

		int r = 255, g = 255, b = 255;
		float *colors = GetClientColor( best_player );
		r *= colors[0];
		g *= colors[1];
		b *= colors[2];

		if(pl_info->thisplayer) // hey, it's me!
		{
			FillRGBABlend( xstart, ypos, xend - xstart, ROW_GAP, 255, 255, 255, 15 );
		}

		DrawUtils::DrawHudString( g_Columns[COL_NAME].start + nameoffset, ypos, g_Columns[COL_NAME].start + 350, pl_info->name, r, g, b );

		if( cl_showplayerversion->value == 0.0f )
		{
			if( team && stricmp( team, "SPECTATOR" ))
			{
				// draw bomb( if player have the bomb )
				if( g_PlayerExtraInfo[best_player].dead )
					DrawUtils::DrawHudStringReverse( g_Columns[COL_ATTRIB].start, ypos, g_Columns[COL_ATTRIB].end, Localize( "#Cstrike_DEAD" ), r, g, b );
				else if( g_PlayerExtraInfo[best_player].has_c4 )
					DrawUtils::DrawHudStringReverse( g_Columns[COL_ATTRIB].start, ypos, g_Columns[COL_ATTRIB].end, Localize( "#Cstrike_BOMB" ), r, g, b );
				else if( g_PlayerExtraInfo[best_player].vip )
					DrawUtils::DrawHudStringReverse( g_Columns[COL_ATTRIB].start, ypos, g_Columns[COL_ATTRIB].end, Localize( "#Cstrike_VIP" ),  r, g, b );
				else if (g_PlayerExtraInfo[best_player].has_defuse_kit )
					DrawUtils::DrawHudStringReverse( g_Columns[COL_ATTRIB].start, ypos, g_Columns[COL_ATTRIB].end, Localize( "#Cstrike_DEFUSE_KIT" ),  r, g, b );
			}
		}
		else
		{
			DrawUtils::DrawHudStringReverse( g_Columns[COL_ATTRIB].start, ypos, g_Columns[COL_ATTRIB].end, gEngfuncs.PlayerInfo_ValueForKey( best_player, "cscl_ver" ),  r, g, b );
		}

		if ( g_PlayerExtraInfo[best_player].sb_health >= 0 && !g_PlayerExtraInfo[best_player].dead )
		{
			if ( gHUD.m_pShowHealth->value )
			{
				static char buf[64];
				sprintf( buf, "%d", g_PlayerExtraInfo[best_player].sb_health );
				DrawUtils::DrawHudStringReverse( g_Columns[COL_HP].start, ypos, g_Columns[COL_HP].end, buf, r, g, b );
			}
		}

		if ( g_PlayerExtraInfo[best_player].sb_account >= 0 )
		{
			if ( gHUD.m_pShowMoney->value )
			{
				static char buf[64];
				sprintf( buf, "$%d", g_PlayerExtraInfo[best_player].sb_account );
				DrawUtils::DrawHudStringReverse( g_Columns[COL_MONEY].start, ypos, g_Columns[COL_MONEY].end, buf, r, g, b );
			}
		}

		// draw kills (right to left)
		if( team && stricmp( team, "SPECTATOR" ) )
		{
			DrawUtils::DrawHudNumberString( g_Columns[COL_KILLS].start, ypos, g_Columns[COL_KILLS].end, g_PlayerExtraInfo[best_player].frags, r, g, b );

			// draw deaths
			DrawUtils::DrawHudNumberString( g_Columns[COL_DEATHS].start, ypos, g_Columns[COL_DEATHS].end, g_PlayerExtraInfo[best_player].deaths, r, g, b );
		}

		// draw ping & packetloss
		const char *value;
		if( pl_info->ping <= 5  // must be 0, until Xash's bug not fixed
			&& ( value = gEngfuncs.PlayerInfo_ValueForKey( best_player, "*bot" ) )
			&& atoi( value ) > 0 )
		{
			DrawUtils::DrawHudStringReverse( g_Columns[COL_PING].start, ypos, g_Columns[COL_PING].end, "BOT", r, g, b );
		}
		else
		{
			static char buf[64];
			sprintf( buf, "%d", pl_info->ping );
			DrawUtils::DrawHudStringReverse( g_Columns[COL_PING].start, ypos, g_Columns[COL_PING].end, buf, r, g, b );
		}

		pl_info->name = NULL;  // set the name to be NULL, so this client won't get drawn again
		list_slot++;
	}

	list_slot += 2.0f;

	return list_slot;
}


void CHudScoreboard2 :: GetAllPlayersInfo( void )
{
	memset( &g_PlayerInfoList[0], 0, sizeof( g_PlayerInfoList[0] ));

	for( int i = 1; i < MAX_PLAYERS; i++ )
		GetPlayerInfo( i, &g_PlayerInfoList[i] );
}

int CHudScoreboard2 :: MsgFunc_ScoreInfo( const char *pszName, int iSize, void *pbuf )
{
	m_iFlags |= HUD_DRAW;

	BufferReader reader( pszName, pbuf, iSize );
	short cl = reader.ReadByte();
	short frags = reader.ReadShort();
	short deaths = reader.ReadShort();
	short playerclass = reader.ReadShort();
	reader.ReadShort();

	if ( cl > 0 && cl <= MAX_PLAYERS )
	{
		g_PlayerExtraInfo[cl].frags = frags;
		g_PlayerExtraInfo[cl].deaths = deaths;
		g_PlayerExtraInfo[cl].playerclass = playerclass;

		//gViewPort->UpdateOnPlayerInfo();
	}

	return 1;
}

// Message handler for TeamInfo message
// accepts two values:
//		byte: client number
//		string: client team name
int CHudScoreboard2 :: MsgFunc_TeamInfo( const char *pszName, int iSize, void *pbuf )
{
	BufferReader reader( pszName, pbuf, iSize );
	short cl = reader.ReadByte();
	int teamNumber = 0;

	if ( cl > 0 && cl <= MAX_PLAYERS )
	{
		// set the players team
		char teamName[MAX_TEAM_NAME];
		strncpy( teamName, reader.ReadString(), MAX_TEAM_NAME );
		teamName[MAX_TEAM_NAME-1] = 0;

		if( !strcmp( teamName, "TERRORIST") )
			teamNumber = TEAM_TERRORIST;
		else if( !strcmp( teamName, "CT") )
			teamNumber = TEAM_CT;
		else if( !strcmp( teamName, "SPECTATOR" ) )
		{
			teamNumber = TEAM_SPECTATOR;
		}
		else if( !strcmp( teamName, "UNASSIGNED" ) )
		{
			teamNumber = TEAM_UNASSIGNED;
			strncpy( teamName, "SPECTATOR", MAX_TEAM_NAME );
		}
		// just in case
		else teamNumber = TEAM_UNASSIGNED;

		strncpy( g_PlayerExtraInfo[cl].teamname, teamName, MAX_TEAM_NAME );
		g_PlayerExtraInfo[cl].teamnumber = teamNumber;
	}

	// rebuild the list of teams

	// clear out player counts from teams
	for ( int i = 1; i <= m_iNumTeams; i++ )
	{
		g_TeamInfo[i].players = 0;
	}

	// rebuild the team list
	GetAllPlayersInfo();
	m_iNumTeams = 0;

	for ( int i = 1; i < MAX_PLAYERS; i++ )
	{
		int j;
		//if ( g_PlayerInfoList[i].name == NULL )
		//	continue;

		if ( g_PlayerExtraInfo[i].teamname[0] == 0 )
			continue; // skip over players who are not in a team

		// is this player in an existing team?
		for ( j = 1; j <= m_iNumTeams; j++ )
		{
			if ( g_TeamInfo[j].name[0] == '\0' )
				break;

			if ( !stricmp( g_PlayerExtraInfo[i].teamname, g_TeamInfo[j].name ) )
				break;
		}

		if ( j > m_iNumTeams )
		{
			// they aren't in a listed team, so make a new one
			for ( j = 1; j <= m_iNumTeams; j++ )
			{
				if ( g_TeamInfo[j].name[0] == '\0' )
					break;
			}


			m_iNumTeams = max( j, m_iNumTeams );

			strncpy( g_TeamInfo[j].name, g_PlayerExtraInfo[i].teamname, MAX_TEAM_NAME );
			g_TeamInfo[j].teamnumber = g_PlayerExtraInfo[i].teamnumber;
			g_TeamInfo[j].players = 0;
		}

		g_TeamInfo[j].players++;
	}

	// clear out any empty teams
	for ( int i = 1; i <= m_iNumTeams; i++ )
	{
		if ( g_TeamInfo[i].players < 1 )
			memset( &g_TeamInfo[i], 0, sizeof(team_info_t) );
	}

	return 1;
}

// Message handler for TeamScore message
// accepts three values:
//		string: team name
//		short: teams kills
//		short: teams deaths
// if this message is never received, then scores will simply be the combined totals of the players.
int CHudScoreboard2 :: MsgFunc_TeamScore( const char *pszName, int iSize, void *pbuf )
{
	BufferReader reader( pszName, pbuf, iSize );
	char *TeamName = reader.ReadString();
	int i;

	// find the team matching the name
	for ( i = 0; i < m_iNumTeams; i++ )
	{
		if ( !stricmp( TeamName, g_TeamInfo[i].name ) )
			break;
	}
	if ( i > m_iNumTeams )
	{
		reader.Flush();
		return 1;
	}

	// use this new score data instead of combined player scores
	g_TeamInfo[i].scores_overriden = TRUE;
	g_TeamInfo[i].frags = reader.ReadShort();
	// g_TeamInfo[i].deaths = reader.ReadShort();

	return 1;
}

void CHudScoreboard2 :: DeathMsg( int killer, int victim )
{
	// if we were the one killed,  or the world killed us, set the scoreboard to indicate suicide
	if ( victim == m_iPlayerNum || killer == 0 )
	{
		m_iLastKilledBy = killer ? killer : m_iPlayerNum;
		m_fLastKillTime = gHUD.m_flTime + 10;	// display who we were killed by for 10 seconds

		if ( killer == m_iPlayerNum )
			m_iLastKilledBy = m_iPlayerNum;
	}
}



void CHudScoreboard2 :: UserCmd_ShowScores( void )
{
	m_bForceDraw = false;
	m_bShowscoresHeld = true;
	m_iFlags |= HUD_DRAW;
}

void CHudScoreboard2 :: UserCmd_HideScores( void )
{
	m_bForceDraw = m_bShowscoresHeld = false;
}
