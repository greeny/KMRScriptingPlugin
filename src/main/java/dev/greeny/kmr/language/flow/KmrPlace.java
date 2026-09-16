package dev.greeny.kmr.language.flow;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.KmrPascalParameterDeclaration;
import dev.greeny.kmr.language.psi.KmrPascalParameterIdentifier;
import dev.greeny.kmr.language.psi.KmrPascalRoutineDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Something whose value the flow analysis follows: a variable ({@code U}), a field of one ({@code Rec.X}, also through
 * {@code with}), an element addressed by constant indexes ({@code Slots[3]}), or a deterministic call with given
 * arguments ({@code States.UnitOwner(U)}). Declarations are compared by identity: all places of one analysis come
 * from the same PSI tree.
 */
public final class KmrPlace
{

	public enum Kind
	{
		VARIABLE, FIELD, INDEX, CALL
	}

	public final Kind kind;
	/** VARIABLE: the var/parameter identifier (or the routine for {@code Result}); FIELD: the field; CALL: the callee. */
	@NotNull
	public final PsiElement declaration;
	/** FIELD/INDEX: the place this is part of. */
	@Nullable
	public final KmrPlace base;
	/** INDEX: the constant index at each level. */
	@NotNull
	public final List<KmrConst> indexes;
	/** CALL: the arguments, whitespace- and case-normalized. */
	@NotNull
	public final List<String> arguments;
	/** CALL: every place an argument reads; a write to any of them changes the call's value. */
	@NotNull
	public final Set<KmrPlace> dependencies;
	/** CALL: the value depends on the game state ({@code States}, or an argument does). */
	public final boolean gameState;
	/** Presentation text: {@code Rec.X}, {@code States.UnitOwner(U)}. */
	@NotNull
	public final String text;

	private KmrPlace(@NotNull Kind kind, @NotNull PsiElement declaration, @Nullable KmrPlace base, @NotNull List<KmrConst> indexes,
					 @NotNull List<String> arguments, @NotNull Set<KmrPlace> dependencies, boolean gameState, @NotNull String text)
	{
		this.kind = kind;
		this.declaration = declaration;
		this.base = base;
		this.indexes = indexes;
		this.arguments = arguments;
		this.dependencies = dependencies;
		this.gameState = gameState;
		this.text = text;
	}

	@NotNull
	public static KmrPlace variable(@NotNull PsiElement declaration, @NotNull String text)
	{
		return new KmrPlace(Kind.VARIABLE, declaration, null, Collections.emptyList(), Collections.emptyList(), Collections.emptySet(), false, text);
	}

	@NotNull
	public static KmrPlace field(@NotNull KmrPlace base, @NotNull PsiElement field, @NotNull String name)
	{
		return new KmrPlace(Kind.FIELD, field, base, Collections.emptyList(), Collections.emptyList(), Collections.emptySet(), false, base.text + "." + name);
	}

	@NotNull
	public static KmrPlace index(@NotNull KmrPlace base, @NotNull List<KmrConst> indexes)
	{
		StringBuilder text = new StringBuilder(base.text).append('[');
		for (int i = 0; i < indexes.size(); i++) {
			text.append(i > 0 ? ", " : "").append(indexes.get(i));
		}
		return new KmrPlace(Kind.INDEX, base.declaration, base, indexes, Collections.emptyList(), Collections.emptySet(), false, text.append(']').toString());
	}

	@NotNull
	public static KmrPlace call(@NotNull PsiElement callee, @NotNull List<String> arguments, @NotNull Set<KmrPlace> dependencies, boolean gameState, @NotNull String text)
	{
		return new KmrPlace(Kind.CALL, callee, null, Collections.emptyList(), arguments, dependencies, gameState, text);
	}

	public boolean isCall()
	{
		return kind == Kind.CALL;
	}

	/** The variable this place is (part of); null for calls. */
	@Nullable
	public KmrPlace root()
	{
		if (kind == Kind.CALL) {
			return null;
		}
		KmrPlace place = this;
		while (place.base != null) {
			place = place.base;
		}
		return place;
	}

	/**
	 * Whether the place lives entirely inside the routine: a local variable, a by-value parameter or the result. A
	 * {@code var} parameter may alias a global, so it counts as global; so does a call reading any global.
	 */
	public boolean isLocalTo(@NotNull KmrPascalRoutineDeclaration routine)
	{
		if (kind == Kind.CALL) {
			for (KmrPlace dependency : dependencies) {
				if (!dependency.isLocalTo(routine)) {
					return false;
				}
			}
			return true;
		}
		KmrPlace root = root();
		PsiElement declaration = root == null ? this.declaration : root.declaration;
		if (declaration instanceof KmrPascalParameterIdentifier) {
			PsiElement group = declaration.getParent();
			if (group instanceof KmrPascalParameterDeclaration && KmrPurity.isByReference((KmrPascalParameterDeclaration) group)) {
				return false;
			}
		}
		return PsiTreeUtil.isAncestor(routine, declaration, false);
	}

	/** Whether a write to {@code written} may change this place's value. */
	public boolean overlaps(@NotNull KmrPlace written)
	{
		if (kind == Kind.CALL) {
			for (KmrPlace dependency : dependencies) {
				if (dependency.overlaps(written)) {
					return true;
				}
			}
			return false;
		}
		if (written.kind == Kind.CALL) {
			return false;
		}
		return isPrefixOf(written) || written.isPrefixOf(this);
	}

	/** Whether {@code other} is this place or a part of it ({@code Rec} is a prefix of {@code Rec.X}). */
	private boolean isPrefixOf(@NotNull KmrPlace other)
	{
		for (KmrPlace place = other; place != null; place = place.base) {
			if (place.equals(this)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o) {
			return true;
		}
		if (!(o instanceof KmrPlace)) {
			return false;
		}
		KmrPlace other = (KmrPlace) o;
		return kind == other.kind && declaration == other.declaration && Objects.equals(base, other.base)
			&& indexes.equals(other.indexes) && arguments.equals(other.arguments);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(kind, System.identityHashCode(declaration), base, indexes, arguments);
	}

	@Override
	public String toString()
	{
		return text;
	}

}
