package dev.greeny.kmr.language.flow;

import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the flow analysis knows about the value of one place at one point: the exact value, an integer range and/or
 * values it is known not to be. Immutable; every narrowing returns a new fact, {@link #CONTRADICTION} when the new
 * information cannot hold together with the old (the code is then unreachable), or null when nothing is known any more.
 */
public final class KmrFact
{

	/** Narrowing led to an impossible value: the branch is unreachable. */
	public static final KmrFact CONTRADICTION = new KmrFact(null, null, null, Collections.emptySet(), null);
	/** No knowledge; only used as the starting point of a narrowing, never stored. */
	private static final KmrFact NOTHING = new KmrFact(null, null, null, Collections.emptySet(), null);

	/** The exact value, when known. */
	@Nullable
	public final KmrConst exact;
	/** Inclusive integer bounds, either may be missing. */
	@Nullable
	public final Long min;
	@Nullable
	public final Long max;
	/** Values the place is known to differ from (when {@link #exact} is unknown). */
	@NotNull
	public final Set<KmrConst> excluded;
	/** The condition or assignment the fact stems from (for messages); null for internal values. */
	@Nullable
	public final PsiElement origin;

	private KmrFact(@Nullable KmrConst exact, @Nullable Long min, @Nullable Long max, @NotNull Set<KmrConst> excluded, @Nullable PsiElement origin)
	{
		this.exact = exact;
		this.min = min;
		this.max = max;
		this.excluded = excluded;
		this.origin = origin;
	}

	@NotNull
	public static KmrFact exact(@NotNull KmrConst value, @Nullable PsiElement origin)
	{
		Long number = value.is(KmrConst.Kind.INTEGER) ? Long.valueOf(value.number) : null;
		return new KmrFact(value, number, number, Collections.emptySet(), origin);
	}

	@Nullable
	public static KmrFact range(@Nullable Long min, @Nullable Long max, @Nullable PsiElement origin)
	{
		return normalize(null, min, max, Collections.emptySet(), origin);
	}

	public boolean isContradiction()
	{
		return this == CONTRADICTION;
	}

	/** Same knowledge, attributed to another origin. */
	@NotNull
	public KmrFact withOrigin(@Nullable PsiElement origin)
	{
		return isContradiction() ? this : new KmrFact(exact, min, max, excluded, origin);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// narrowing
	// ---------------------------------------------------------------------------------------------------------------

	/** {@link #narrow(IElementType, KmrConst, PsiElement)} of possibly absent knowledge. */
	@Nullable
	public static KmrFact narrow(@Nullable KmrFact fact, @NotNull IElementType operator, @NotNull KmrConst value, @Nullable PsiElement origin)
	{
		KmrFact result = (fact == null ? NOTHING : fact).narrow(operator, value, origin);
		return result == NOTHING ? null : result;
	}

	/** Knowledge after learning {@code place op value} (op is one of the comparison tokens). */
	@Nullable
	public KmrFact narrow(@NotNull IElementType operator, @NotNull KmrConst value, @Nullable PsiElement origin)
	{
		if (operator == KmrPascalTypes.EQ) {
			return narrowEquals(value, origin);
		}
		if (operator == KmrPascalTypes.NOTEQ) {
			return narrowNotEquals(value, origin);
		}
		if (!value.is(KmrConst.Kind.INTEGER)) {
			return this;
		}
		if (operator == KmrPascalTypes.LT) return narrowBounds(null, value.number - 1, origin);
		if (operator == KmrPascalTypes.LE) return narrowBounds(null, value.number, origin);
		if (operator == KmrPascalTypes.GT) return narrowBounds(value.number + 1, null, origin);
		if (operator == KmrPascalTypes.GE) return narrowBounds(value.number, null, origin);
		return this;
	}

	@NotNull
	public KmrFact narrowEquals(@NotNull KmrConst value, @Nullable PsiElement origin)
	{
		if (test(KmrPascalTypes.EQ, value) == KmrTruth.FALSE) {
			return CONTRADICTION;
		}
		return exact(value, origin);
	}

	@Nullable
	public KmrFact narrowNotEquals(@NotNull KmrConst value, @Nullable PsiElement origin)
	{
		if (exact != null) {
			return exact.equals(value) ? CONTRADICTION : this;
		}
		Set<KmrConst> newExcluded = new LinkedHashSet<>(excluded);
		newExcluded.add(value);
		return normalize(null, min, max, newExcluded, origin);
	}

	@Nullable
	private KmrFact narrowBounds(@Nullable Long newMin, @Nullable Long newMax, @Nullable PsiElement origin)
	{
		if (exact != null) {
			if (!exact.is(KmrConst.Kind.INTEGER)) {
				return this;
			}
			boolean fits = (newMin == null || exact.number >= newMin) && (newMax == null || exact.number <= newMax);
			return fits ? this : CONTRADICTION;
		}
		Long lo = min;
		if (newMin != null) {
			lo = lo == null ? newMin : Long.valueOf(Math.max(lo, newMin));
		}
		Long hi = max;
		if (newMax != null) {
			hi = hi == null ? newMax : Long.valueOf(Math.min(hi, newMax));
		}
		return normalize(null, lo, hi, excluded, origin);
	}

	/** The canonical fact for the given knowledge: null for "nothing", {@link #CONTRADICTION} for "impossible". */
	@Nullable
	private static KmrFact normalize(@Nullable KmrConst exact, @Nullable Long min, @Nullable Long max, @NotNull Set<KmrConst> excluded, @Nullable PsiElement origin)
	{
		if (exact != null) {
			return excluded.contains(exact) ? CONTRADICTION : exact(exact, origin);
		}
		Long lo = min;
		Long hi = max;
		if (lo != null || hi != null) {
			// shave excluded values off the ends of the range
			boolean changed = true;
			while (changed) {
				changed = false;
				if (lo != null && excluded.contains(KmrConst.ofInt(lo))) { lo++; changed = true; }
				if (hi != null && excluded.contains(KmrConst.ofInt(hi))) { hi--; changed = true; }
				if (lo != null && hi != null && lo > hi) {
					return CONTRADICTION;
				}
			}
			if (lo != null && lo.equals(hi)) {
				return exact(KmrConst.ofInt(lo), origin);
			}
		}
		Set<KmrConst> kept = new LinkedHashSet<>();
		for (KmrConst value : excluded) {
			boolean outside = value.is(KmrConst.Kind.INTEGER) && (lo != null && value.number < lo || hi != null && value.number > hi);
			if (!outside) {
				kept.add(value);
			}
		}
		if (kept.contains(KmrConst.TRUE) && kept.contains(KmrConst.FALSE)) {
			return CONTRADICTION;
		}
		if (kept.contains(KmrConst.TRUE) && kept.size() == 1 && lo == null && hi == null) {
			return exact(KmrConst.FALSE, origin);
		}
		if (kept.contains(KmrConst.FALSE) && kept.size() == 1 && lo == null && hi == null) {
			return exact(KmrConst.TRUE, origin);
		}
		if (lo == null && hi == null && kept.isEmpty()) {
			return null;
		}
		return new KmrFact(null, lo, hi, Collections.unmodifiableSet(kept), origin);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// evaluation and joining
	// ---------------------------------------------------------------------------------------------------------------

	/** Whether {@code place op value} holds given this fact. */
	@NotNull
	public KmrTruth test(@NotNull IElementType operator, @NotNull KmrConst value)
	{
		if (exact != null) {
			return KmrConstEvaluator.compare(operator, exact, value);
		}
		if (operator == KmrPascalTypes.EQ || operator == KmrPascalTypes.NOTEQ) {
			boolean impossible = excluded.contains(value)
				|| value.is(KmrConst.Kind.INTEGER) && (min != null && value.number < min || max != null && value.number > max);
			return impossible ? KmrTruth.of(operator == KmrPascalTypes.NOTEQ) : KmrTruth.UNKNOWN;
		}
		if (!value.is(KmrConst.Kind.INTEGER)) {
			return KmrTruth.UNKNOWN;
		}
		long v = value.number;
		if (operator == KmrPascalTypes.LT) return max != null && max < v ? KmrTruth.TRUE : min != null && min >= v ? KmrTruth.FALSE : KmrTruth.UNKNOWN;
		if (operator == KmrPascalTypes.LE) return max != null && max <= v ? KmrTruth.TRUE : min != null && min > v ? KmrTruth.FALSE : KmrTruth.UNKNOWN;
		if (operator == KmrPascalTypes.GT) return min != null && min > v ? KmrTruth.TRUE : max != null && max <= v ? KmrTruth.FALSE : KmrTruth.UNKNOWN;
		if (operator == KmrPascalTypes.GE) return min != null && min >= v ? KmrTruth.TRUE : max != null && max < v ? KmrTruth.FALSE : KmrTruth.UNKNOWN;
		return KmrTruth.UNKNOWN;
	}

	/** What holds on both paths: the common exact value, the hull of the ranges, the values excluded by both. */
	@Nullable
	public static KmrFact join(@Nullable KmrFact a, @Nullable KmrFact b)
	{
		if (a == null || b == null) {
			return null;
		}
		if (a.isContradiction()) {
			return b;
		}
		if (b.isContradiction()) {
			return a;
		}
		if (a.exact != null && a.exact.equals(b.exact)) {
			return a;
		}
		Long lo = a.min == null || b.min == null ? null : Long.valueOf(Math.min(a.min, b.min));
		Long hi = a.max == null || b.max == null ? null : Long.valueOf(Math.max(a.max, b.max));
		Set<KmrConst> excluded = new LinkedHashSet<>();
		for (KmrConst value : a.excludedValues()) {
			if (b.test(KmrPascalTypes.NOTEQ, value) == KmrTruth.TRUE) {
				excluded.add(value);
			}
		}
		for (KmrConst value : b.excludedValues()) {
			if (a.test(KmrPascalTypes.NOTEQ, value) == KmrTruth.TRUE) {
				excluded.add(value);
			}
		}
		return normalize(null, lo, hi, excluded, a.origin);
	}

	/** Values explicitly known to differ: the excluded set, or for a boolean/enum exact value its complement where finite. */
	@NotNull
	private Set<KmrConst> excludedValues()
	{
		if (exact == null) {
			return excluded;
		}
		KmrConst other = exact.negate();
		return other == null ? Collections.emptySet() : Collections.singleton(other);
	}

	/** {@code True}, {@code 3}, {@code not 3}, {@code 1..5}, {@code >= 3}, {@code <= 5, not 2} — for hover and messages. */
	@NotNull
	public String describe()
	{
		if (exact != null) {
			return exact.toString();
		}
		List<String> parts = new ArrayList<>();
		if (min != null && max != null) {
			parts.add(min + ".." + max);
		} else if (min != null) {
			parts.add(">= " + min);
		} else if (max != null) {
			parts.add("<= " + max);
		}
		if (!excluded.isEmpty()) {
			StringBuilder sb = new StringBuilder("not ");
			boolean first = true;
			for (KmrConst value : excluded) {
				sb.append(first ? "" : ", ").append(value);
				first = false;
			}
			parts.add(sb.toString());
		}
		return String.join(", ", parts);
	}

	@Override
	public String toString()
	{
		return isContradiction() ? "<contradiction>" : describe();
	}

}
