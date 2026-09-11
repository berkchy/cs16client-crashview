/* Menu-based runtime verification tool for the CS16Client gamedata
 * override + ZP OFFSET_ACTIVE_ITEM fix. Turkish-friendly labels kept
 * short; values are printed in chat so they can be compared against the
 * expected arm64 offsets.
 *
 * Commands:
 *   say menu_test   -> open the test menu
 *   menu_test       -> open the test menu
 */
#include <amxmodx>
#include <amxmisc>
#include <cstrike>
#include <fakemeta>
#include <hamsandwich>

#define PLUGIN "Gamedata Menu Test"
#define VERSION "1.0"
#define AUTHOR "opencode"

#define OFFSET_ACTIVE_ITEM 419

new const g_wpn_classnames[][] = {
	"", "weapon_p228", "", "weapon_scout", "weapon_hegrenade", "weapon_xm1014",
	"weapon_c4", "weapon_mac10", "weapon_aug", "weapon_smokegrenade", "weapon_elite",
	"weapon_fiveseven", "weapon_ump45", "weapon_sg550", "weapon_galil", "weapon_famas",
	"weapon_usp", "weapon_glock18", "weapon_awp", "weapon_mp5navy", "weapon_m249",
	"weapon_m3", "weapon_m4a1", "weapon_tmp", "weapon_g3sg1", "weapon_flashbang",
	"weapon_deagle", "weapon_sg552", "weapon_ak47", "weapon_knife", "weapon_p90"
};

public plugin_init()
{
	register_plugin(PLUGIN, VERSION, AUTHOR);
	register_clcmd("say menu_test", "cmd_menu", 0);
	register_clcmd("menu_test", "cmd_menu", 0);
}

public cmd_menu(id)
{
	if (!is_user_alive(id))
	{
		client_print(id, print_chat, "[MT] %s", "hayattayken acilir.");
		return PLUGIN_HANDLED;
	}
	show_main_menu(id);
	return PLUGIN_HANDLED;
}

show_main_menu(id)
{
	new menu = menu_create("\yGamedata Test\w", "menu_handler");
	menu_additem(menu, "Para (okuma/yazma)", "1");
	menu_additem(menu, "Mermi: Sarjor Doldur", "2");
	menu_additem(menu, "Mermi: Bosalt", "3");
	menu_additem(menu, "Takim Degistir", "4");
	menu_additem(menu, "Aktif Silah (ent)", "5");
	menu_additem(menu, "Bilgi Dump", "6");
	menu_setprop(menu, MPROP_EXIT, MEXIT_ALL);
	menu_display(id, menu, 0);
}

public menu_handler(id, menu, item)
{
	if (item == MENU_EXIT)
	{
		menu_destroy(menu);
		return PLUGIN_HANDLED;
	}
	new data[4], name[32], access, callback;
	menu_item_getinfo(menu, item, access, data, charsmax(data), name, charsmax(name), callback);
	new option = str_to_num(data);
	menu_destroy(menu);

	switch (option)
	{
		case 1: money_test(id);
		case 2: ammo_reload(id);
		case 3: ammo_unload(id);
		case 4: team_toggle(id);
		case 5: active_item_info(id);
		case 6: info_dump(id);
	}
	show_main_menu(id);
	return PLUGIN_HANDLED;
}

money_test(id)
{
	new money = cs_get_user_money(id);
	new half = (money + 8000 > 16000) ? (money - 8000) : (money + 8000);
	if (half < 0)
		half = 0;
	cs_set_user_money(id, half);
	client_print(id, print_chat, "[MT] Para: once %d -> simdi %d (gamedata m_iAccount=580)", money, cs_get_user_money(id));
}

ammo_reload(id)
{
	new wpn = cs_get_user_weapon_entity(id);
	if (!pev_valid(wpn))
	{
		client_print(id, print_chat, "[MT] Aktif silah ent yok.");
		return;
	}
	new clip = cs_get_weapon_ammo(wpn);
	cs_set_weapon_ammo(wpn, 99);
	client_print(id, print_chat, "[MT] Sarjor: %d -> 99 (wpn=%d, gamedata m_iClip)", clip, wpn);
}

ammo_unload(id)
{
	new wpn = cs_get_user_weapon_entity(id);
	if (!pev_valid(wpn))
		return;
	new clip = cs_get_weapon_ammo(wpn);
	cs_set_weapon_ammo(wpn, 0);
	client_print(id, print_chat, "[MT] Sarjor: %d -> 0", clip);
}

team_toggle(id)
{
	new CsTeams:team = cs_get_user_team(id);
	cs_set_user_team(id, (team == CS_TEAM_CT) ? CS_TEAM_T : CS_TEAM_CT);
	client_print(id, print_chat, "[MT] Takim: %d -> %d (gamedata m_iTeam=576)", team, cs_get_user_team(id));
	ExecuteHam(Ham_CS_RoundRespawn, id);
}

active_item_info(id)
{
	new wpn = get_pdata_cbase(id, OFFSET_ACTIVE_ITEM);
	new weaponid = cs_get_user_weapon(id);
	if (!pev_valid(wpn))
	{
		client_print(id, print_chat, "[MT] aktif silah ent yok (%d). OFFSET_ACTIVE_ITEM=419 sorunlu olabilir.", wpn);
		return;
	}
	new classname[32];
	pev(wpn, pev_classname, classname, charsmax(classname));
	client_print(id, print_chat, "[MT] aktif silah ent=%d id=%d classname='%s' (pdata 419)", wpn, weaponid, classname);
	client_print(id, print_chat, "[MT] beklenen: weaponid=%d -> %s", weaponid, g_wpn_classnames[weaponid]);
}

info_dump(id)
{
	new clip, ammo;
	new weapon = cs_get_user_weapon(id, clip, ammo);
	new wpn = cs_get_user_weapon_entity(id);
	new money = cs_get_user_money(id);
	new CsTeams:team = cs_get_user_team(id);
	new deaths = cs_get_user_deaths(id);
	client_print(id, print_chat, "[MT] para=%d team=%d deaths=%d active_wpn=%d ent=%d", money, team, deaths, weapon, wpn);
	client_print(id, print_chat, "[MT] aktif clip=%d ammo=%d (gamedata m_iClip / m_rgAmmo)", clip, ammo);
}