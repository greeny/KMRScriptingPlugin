package dev.greeny.kmr.language.stubs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The integer ids of the old script API and the enum members the {@code ...Ex} functions take instead: the game's
 * lookup arrays ({@code UNIT_ID_TO_TYPE}, {@code HOUSE_ID_TO_TYPE}, {@code WARE_ID_TO_TYPE} in {@code KM_Res*.pas},
 * {@code TKMGroupType(id + GROUP_TYPE_MIN_OFF)}, {@code TKMDirection(id + 1)}, {@code TKMTerrainPassability(id)}), as
 * published on the wiki page "Lookup Tables (Mission Script Dynamic)".
 * <p>
 * Ids are stable across game versions; the enum member names are those of {@code Types.script}, so a member is only
 * ever inserted after the stubs of the file's version have been checked to declare it.
 */
public final class KmrPascalLegacyIds
{

	private static final Map<String, Map<Integer, String>> IDS = new HashMap<>();
	/** {@code -1} in the old {@code Closest*} functions: "any type". */
	private static final Map<String, String> ANY = new HashMap<>();
	/** {@code -1} as the old functions' result: "no such thing". */
	private static final Map<String, String> NONE = new HashMap<>();

	static {
		// index 28/29 (siege weapons) and 38..40 are utNone in UNIT_ID_TO_TYPE
		declare("TKMUnitType", "utAny", "utNone", Arrays.asList(
			"utSerf", "utWoodcutter", "utMiner", "utAnimalBreeder", "utFarmer",
			"utCarpenter", "utBaker", "utButcher", "utFisher", "utBuilder",
			"utStonemason", "utSmith", "utMetallurgist", "utRecruit",
			"utMilitia", "utAxeFighter", "utSwordFighter", "utBowman", "utCrossbowman",
			"utLanceCarrier", "utPikeman", "utScout", "utKnight", "utBarbarian",
			"utRebel", "utRogue", "utWarrior", "utVagabond",
			null, null,
			"utWolf", "utFish", "utWatersnake", "utSeastar", "utCrab", "utWaterflower", "utWaterleaf", "utDuck"));
		// index 26 is unused (htNone) in HOUSE_ID_TO_TYPE
		declare("TKMHouseType", "htAny", "htNone", Arrays.asList(
			"htSawmill", "htIronSmithy", "htWeaponSmithy", "htCoalMine", "htIronMine",
			"htGoldMine", "htFishermans", "htBakery", "htFarm", "htWoodcutters",
			"htArmorSmithy", "htStore", "htStables", "htSchool", "htQuarry",
			"htMetallurgists", "htSwine", "htWatchTower", "htTownHall", "htWeaponWorkshop",
			"htArmorWorkshop", "htBarracks", "htMill", "htSiegeWorkshop", "htButchers",
			"htTannery", null, "htInn", "htVineyard", "htMarket"));
		declare("TKMWareType", null, "wtNone", Arrays.asList(
			"wtTrunk", "wtStone", "wtTimber", "wtIronOre", "wtGoldOre",
			"wtCoal", "wtIron", "wtGold", "wtWine", "wtCorn",
			"wtBread", "wtFlour", "wtLeather", "wtSausage", "wtPig",
			"wtSkin", "wtWoodenShield", "wtIronShield", "wtLeatherArmor", "wtIronArmor",
			"wtAxe", "wtSword", "wtLance", "wtPike", "wtBow",
			"wtCrossbow", "wtHorse", "wtFish"));
		declare("TKMGroupType", "gtAny", "gtNone", Arrays.asList("gtMelee", "gtAntiHorse", "gtRanged", "gtMounted"));
		// 7 0 1 / 6 x 2 / 5 4 3 around the unit
		declare("TKMDirection", null, "dirNA", Arrays.asList("dirN", "dirNE", "dirE", "dirSE", "dirS", "dirSW", "dirW", "dirNW"));
		declare("TKMTerrainPassability", null, null, Arrays.asList(
			"tpNone", "tpWalk", "tpWalkRoad", "tpBuildNoObj", "tpBuild", "tpMakeRoads", "tpCutTree",
			"tpFish", "tpCrab", "tpWolf", "tpElevate", "tpWorker", "tpOwn", "tpFactor"));
		declare("TKMAIDefencePosType", null, null, Arrays.asList("dtFrontLine", "dtBackLine"));
	}

	private KmrPascalLegacyIds()
	{
	}

	private static void declare(@NotNull String enumType, @Nullable String any, @Nullable String none, @NotNull List<String> membersById)
	{
		String key = enumType.toLowerCase(Locale.ROOT);
		Map<Integer, String> ids = new HashMap<>();
		for (int id = 0; id < membersById.size(); id++) {
			if (membersById.get(id) != null) {
				ids.put(id, membersById.get(id));
			}
		}
		IDS.put(key, ids);
		if (any != null) {
			ANY.put(key, any);
		}
		if (none != null) {
			NONE.put(key, none);
		}
	}

	/** Whether the old API passed values of this enum type as integer ids. */
	public static boolean isMapped(@NotNull String enumType)
	{
		return IDS.containsKey(enumType.toLowerCase(Locale.ROOT));
	}

	/**
	 * The enum member for an id passed as a parameter, or null when the id is invalid. {@code -1} is the member
	 * meaning "any" when {@code anyAllowed} (the old {@code Closest*} functions accept it), invalid otherwise.
	 */
	@Nullable
	public static String parameterValue(@NotNull String enumType, int id, boolean anyAllowed)
	{
		String key = enumType.toLowerCase(Locale.ROOT);
		if (id == -1) {
			return anyAllowed ? ANY.get(key) : null;
		}
		Map<Integer, String> ids = IDS.get(key);
		return ids == null ? null : ids.get(id);
	}

	/** The enum member an old function's integer result compares to; {@code -1} is the "none" member. */
	@Nullable
	public static String resultValue(@NotNull String enumType, int id)
	{
		String key = enumType.toLowerCase(Locale.ROOT);
		if (id == -1) {
			return NONE.get(key);
		}
		Map<Integer, String> ids = IDS.get(key);
		return ids == null ? null : ids.get(id);
	}

}
