package dev.greeny.kmr.language.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Identifiers PascalScript itself provides and that therefore never resolve to a declaration: the built-in types
 * (from {@code TPSPascalCompiler.DefineStandardTypes} of the reyandme/pascalscript fork the game embeds). The
 * standard routines ({@code Inc}, {@code Length}, {@code IntToStr}, ...) are described by the {@code System.script}
 * stub instead, so they resolve, complete and document like API members.
 * <p>
 * Also knows common Delphi/Free Pascal routines that do <em>not</em> exist in the game's PascalScript, with the
 * KaM Remake replacement to suggest.
 */
public final class KmrPascalBuiltins
{

	private KmrPascalBuiltins()
	{
	}

	/** Type names offered by completion: the useful part of what the compiler defines. */
	public static final List<String> TYPES = List.of(
		"Integer", "Cardinal", "Byte", "ShortInt", "SmallInt", "Word", "LongInt", "LongWord", "Int64",
		"Single", "Double", "Extended", "Currency", "Boolean", "LongBool", "WordBool", "ByteBool",
		"Char", "AnsiChar", "WideChar", "String", "AnsiString", "WideString", "UnicodeString", "Variant"
	);

	/** Further type names the compiler accepts (internal aliases), valid in scripts but not worth completing. */
	public static final List<String> TYPE_ALIASES = List.of("AnyString", "NativeString", "tbtString", "AnyMethod", "PChar", "PAnsiChar");

	/**
	 * Routines people know from Delphi / Free Pascal that KaM Remake's PascalScript does not have, mapped to what to
	 * use instead (the game registers only the compiler's core procedures, see {@code stubs/system/System.script}).
	 */
	private static final Map<String, String> MISSING_ROUTINES = Map.ofEntries(
		Map.entry("random", "States.KaMRandom / States.KaMRandomI or Utils.RandomRangeI"),
		Map.entry("randomrange", "Utils.RandomRangeI"),
		Map.entry("randomize", "nothing: the game seeds States.KaMRandom itself"),
		Map.entry("min", "Utils.MinI / Utils.MinS"),
		Map.entry("max", "Utils.MaxI / Utils.MaxS"),
		Map.entry("ensurerange", "Utils.EnsureRangeI / Utils.EnsureRangeS"),
		Map.entry("inrange", "Utils.InRangeI / Utils.InRangeS"),
		Map.entry("format", "Utils.Format"),
		Map.entry("formatfloat", "Utils.FormatFloat"),
		Map.entry("booltostr", "Utils.BoolToStr"),
		Map.entry("ifthen", "Utils.IfThen / Utils.IfThenI / Utils.IfThenS"),
		Map.entry("sqr", "Utils.Sqr"),
		Map.entry("power", "Utils.Power"),
		Map.entry("ln", "Utils.Ln"),
		Map.entry("log10", "Utils.Log10"),
		Map.entry("log2", "Utils.Log2"),
		Map.entry("logn", "Utils.LogN"),
		Map.entry("ceil", "Utils.CeilTo"),
		Map.entry("floor", "Utils.FloorTo"),
		Map.entry("roundto", "Utils.RoundTo"),
		Map.entry("trimleft", "Utils.TrimLeft"),
		Map.entry("trimright", "Utils.TrimRight"),
		Map.entry("stringreplace", "Utils.StringReplace"),
		Map.entry("comparestr", "Utils.CompareString"),
		Map.entry("comparetext", "Utils.CompareText"),
		Map.entry("sametext", "Utils.CompareText(...) = 0"),
		Map.entry("inttohex", "nothing: format the number yourself"),
		Map.entry("exp", "nothing (no Exp in the game's PascalScript)"),
		Map.entry("tan", "nothing (no Tan in the game's PascalScript)"),
		Map.entry("arctan", "nothing (no ArcTan in the game's PascalScript)"),
		Map.entry("frac", "E - Int(E)"),
		Map.entry("now", "States.GameTime (ticks) or Utils.TimeToString"),
		Map.entry("time", "States.GameTime (ticks) or Utils.TimeToString"),
		Map.entry("date", "nothing: scripts cannot read the clock"),
		Map.entry("sleep", "nothing: schedule work in OnTick instead"),
		Map.entry("writeln", "Actions.Log or Actions.ShowMsg"),
		Map.entry("showmessage", "Actions.ShowMsg"),
		Map.entry("exit", "the exit statement (without parentheses)"),
		Map.entry("halt", "nothing: let the event handler return")
	);

	private static final Set<String> TYPE_SET = lowerSet(TYPES);
	private static final Set<String> TYPE_ALIAS_SET = lowerSet(TYPE_ALIASES);

	/** Whether the name is a type the game's PascalScript defines (including internal aliases). */
	public static boolean isBuiltinType(@NotNull String name)
	{
		String key = name.toLowerCase(Locale.ROOT);
		return TYPE_SET.contains(key) || TYPE_ALIAS_SET.contains(key);
	}

	/** What to use instead of a Delphi routine that is missing in the game's PascalScript, or null if unknown. */
	@Nullable
	public static String replacementFor(@NotNull String missingRoutine)
	{
		return MISSING_ROUTINES.get(missingRoutine.toLowerCase(Locale.ROOT));
	}

	private static Set<String> lowerSet(List<String> names)
	{
		Set<String> set = new TreeSet<>();
		for (String name : names) {
			set.add(name.toLowerCase(Locale.ROOT));
		}
		return set;
	}

}
