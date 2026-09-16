package dev.greeny.kmr.language.flow;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * A compile-time constant the flow analysis can compare: a boolean, an integer, a string (a char is a one-letter
 * string), an enum member (identified by its name, which Pascal keeps unique across enums) or {@code nil}.
 */
public final class KmrConst
{

	public enum Kind
	{
		BOOLEAN, INTEGER, STRING, ENUM, NIL
	}

	public static final KmrConst TRUE = new KmrConst(Kind.BOOLEAN, 1, "true", "True");
	public static final KmrConst FALSE = new KmrConst(Kind.BOOLEAN, 0, "false", "False");
	public static final KmrConst NIL = new KmrConst(Kind.NIL, 0, "nil", "nil");

	public final Kind kind;
	/** The number for integers, 0/1 for booleans. */
	public final long number;
	/** Comparison key: the string value, the lower-cased enum name. */
	@NotNull
	private final String key;
	/** How the value is shown in messages. */
	@NotNull
	private final String display;

	private KmrConst(@NotNull Kind kind, long number, @NotNull String key, @NotNull String display)
	{
		this.kind = kind;
		this.number = number;
		this.key = key;
		this.display = display;
	}

	@NotNull
	public static KmrConst of(boolean value)
	{
		return value ? TRUE : FALSE;
	}

	@NotNull
	public static KmrConst ofInt(long value)
	{
		return new KmrConst(Kind.INTEGER, value, Long.toString(value), Long.toString(value));
	}

	/** A string value (already decoded: no quotes, {@code #13} resolved). */
	@NotNull
	public static KmrConst ofString(@NotNull String value)
	{
		return new KmrConst(Kind.STRING, 0, value, "'" + value.replace("'", "''") + "'");
	}

	@NotNull
	public static KmrConst ofEnum(@NotNull String memberName)
	{
		return new KmrConst(Kind.ENUM, 0, memberName.toLowerCase(Locale.ROOT), memberName);
	}

	public boolean is(@NotNull Kind kind)
	{
		return this.kind == kind;
	}

	public boolean asBoolean()
	{
		return number != 0;
	}

	/** The decoded text of a string constant, the name of an enum member (lower-cased). */
	@NotNull
	public String stringValue()
	{
		return key;
	}

	/** The other boolean; null for anything but a boolean. */
	@Nullable
	public KmrConst negate()
	{
		return kind == Kind.BOOLEAN ? of(!asBoolean()) : null;
	}

	/** Ordering for {@code <}, {@code <=}, ...: integers, booleans (False < True) and strings compare; null otherwise. */
	@Nullable
	public Integer compareTo(@NotNull KmrConst other)
	{
		if (kind != other.kind) {
			return null;
		}
		switch (kind) {
			case INTEGER:
			case BOOLEAN:
				return Long.compare(number, other.number);
			case STRING:
				return Integer.signum(key.compareTo(other.key));
			default:
				return null;
		}
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof KmrConst)) {
			return false;
		}
		KmrConst other = (KmrConst) o;
		return kind == other.kind && number == other.number && key.equals(other.key);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(kind, number, key);
	}

	@Override
	@NotNull
	public String toString()
	{
		return display;
	}

}
