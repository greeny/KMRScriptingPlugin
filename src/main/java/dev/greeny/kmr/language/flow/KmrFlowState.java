package dev.greeny.kmr.language.flow;

import dev.greeny.kmr.language.psi.KmrPascalExpression;
import dev.greeny.kmr.language.psi.KmrPascalRoutineDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What holds at one point of a routine: value facts per place, and the deterministic calls already evaluated on every
 * path leading here (with their first occurrence). Immutable: every operation returns a new state. The
 * {@link #UNREACHABLE} state stands for code no path reaches (after {@code exit}, or under a contradicting condition).
 */
public final class KmrFlowState
{

	public static final KmrFlowState EMPTY = new KmrFlowState(Collections.emptyMap(), Collections.emptyMap(), true);
	public static final KmrFlowState UNREACHABLE = new KmrFlowState(Collections.emptyMap(), Collections.emptyMap(), false);

	private final Map<KmrPlace, KmrFact> facts;
	private final Map<KmrPlace, KmrPascalExpression> evaluated;
	private final boolean reachable;

	private KmrFlowState(@NotNull Map<KmrPlace, KmrFact> facts, @NotNull Map<KmrPlace, KmrPascalExpression> evaluated, boolean reachable)
	{
		this.facts = facts;
		this.evaluated = evaluated;
		this.reachable = reachable;
	}

	public boolean isReachable()
	{
		return reachable;
	}

	@Nullable
	public KmrFact factOf(@Nullable KmrPlace place)
	{
		return place == null ? null : facts.get(place);
	}

	/** The first evaluation of a deterministic call whose value still holds here, or null. */
	@Nullable
	public KmrPascalExpression evaluationOf(@Nullable KmrPlace place)
	{
		return place == null ? null : evaluated.get(place);
	}

	@NotNull
	public Map<KmrPlace, KmrFact> facts()
	{
		return Collections.unmodifiableMap(facts);
	}

	/** This state knowing the fact (null forgets the place, a contradiction makes the state unreachable). */
	@NotNull
	public KmrFlowState with(@NotNull KmrPlace place, @Nullable KmrFact fact)
	{
		if (!reachable) {
			return this;
		}
		if (fact != null && fact.isContradiction()) {
			return UNREACHABLE;
		}
		Map<KmrPlace, KmrFact> copy = new LinkedHashMap<>(facts);
		if (fact == null) {
			copy.remove(place);
		} else {
			copy.put(place, fact);
		}
		return new KmrFlowState(copy, evaluated, true);
	}

	/** This state remembering that the call was evaluated here (keeps an earlier evaluation). */
	@NotNull
	public KmrFlowState evaluated(@NotNull KmrPlace place, @NotNull KmrPascalExpression call)
	{
		if (!reachable || evaluated.containsKey(place)) {
			return this;
		}
		Map<KmrPlace, KmrPascalExpression> copy = new LinkedHashMap<>(evaluated);
		copy.put(place, call);
		return new KmrFlowState(facts, copy, true);
	}

	/** Forgets everything that a write to the place may have changed. */
	@NotNull
	public KmrFlowState kill(@NotNull KmrPlace written)
	{
		return removeIf(place -> place.overlaps(written));
	}

	/** Forgets every value that depends on the game state (after an {@code Actions} call). */
	@NotNull
	public KmrFlowState killGameState()
	{
		return removeIf(place -> place.isCall() && place.gameState);
	}

	/** Forgets everything not local to the routine (after calling a routine of the script). */
	@NotNull
	public KmrFlowState killGlobals(@NotNull KmrPascalRoutineDeclaration routine)
	{
		return removeIf(place -> !place.isLocalTo(routine));
	}

	@NotNull
	public KmrFlowState killAll()
	{
		return reachable ? EMPTY : this;
	}

	@NotNull
	private KmrFlowState removeIf(@NotNull Predicate<KmrPlace> condition)
	{
		if (!reachable) {
			return this;
		}
		Map<KmrPlace, KmrFact> newFacts = new LinkedHashMap<>();
		for (Map.Entry<KmrPlace, KmrFact> entry : facts.entrySet()) {
			if (!condition.test(entry.getKey())) {
				newFacts.put(entry.getKey(), entry.getValue());
			}
		}
		Map<KmrPlace, KmrPascalExpression> newEvaluated = new LinkedHashMap<>();
		for (Map.Entry<KmrPlace, KmrPascalExpression> entry : evaluated.entrySet()) {
			if (!condition.test(entry.getKey())) {
				newEvaluated.put(entry.getKey(), entry.getValue());
			}
		}
		return new KmrFlowState(newFacts, newEvaluated, true);
	}

	/** What holds after either path: facts on both (joined), evaluations on both (the earlier one). */
	@NotNull
	public static KmrFlowState join(@NotNull KmrFlowState a, @NotNull KmrFlowState b)
	{
		if (!a.reachable) {
			return b;
		}
		if (!b.reachable) {
			return a;
		}
		Map<KmrPlace, KmrFact> facts = new LinkedHashMap<>();
		for (Map.Entry<KmrPlace, KmrFact> entry : a.facts.entrySet()) {
			KmrFact joined = KmrFact.join(entry.getValue(), b.facts.get(entry.getKey()));
			if (joined != null) {
				facts.put(entry.getKey(), joined);
			}
		}
		Map<KmrPlace, KmrPascalExpression> evaluated = new LinkedHashMap<>();
		for (Map.Entry<KmrPlace, KmrPascalExpression> entry : a.evaluated.entrySet()) {
			KmrPascalExpression other = b.evaluated.get(entry.getKey());
			if (other != null) {
				evaluated.put(entry.getKey(), entry.getValue().getTextOffset() <= other.getTextOffset() ? entry.getValue() : other);
			}
		}
		return new KmrFlowState(facts, evaluated, true);
	}

	@Override
	public String toString()
	{
		return reachable ? facts.toString() : "<unreachable>";
	}

}
