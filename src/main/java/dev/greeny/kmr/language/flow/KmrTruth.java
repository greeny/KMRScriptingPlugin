package dev.greeny.kmr.language.flow;

import org.jetbrains.annotations.NotNull;

/** Three-valued result of evaluating a condition against what the flow analysis knows. */
public enum KmrTruth
{
	TRUE, FALSE, UNKNOWN;

	@NotNull
	public static KmrTruth of(boolean value)
	{
		return value ? TRUE : FALSE;
	}

	public boolean isKnown()
	{
		return this != UNKNOWN;
	}

	@NotNull
	public KmrTruth not()
	{
		return this == TRUE ? FALSE : this == FALSE ? TRUE : UNKNOWN;
	}

	@NotNull
	public KmrTruth and(@NotNull KmrTruth other)
	{
		if (this == FALSE || other == FALSE) {
			return FALSE;
		}
		return this == TRUE && other == TRUE ? TRUE : UNKNOWN;
	}

	@NotNull
	public KmrTruth or(@NotNull KmrTruth other)
	{
		if (this == TRUE || other == TRUE) {
			return TRUE;
		}
		return this == FALSE && other == FALSE ? FALSE : UNKNOWN;
	}

	/** {@code xor}: known only when both sides are. */
	@NotNull
	public KmrTruth xor(@NotNull KmrTruth other)
	{
		return isKnown() && other.isKnown() ? of(this != other) : UNKNOWN;
	}
}
