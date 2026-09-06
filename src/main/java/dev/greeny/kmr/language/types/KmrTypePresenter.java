package dev.greeny.kmr.language.types;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.resolve.KmrPascalTypeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Type text for completion, parameter info, documentation and generated headers. Kinds appear in words after the
 * type ({@code Integer (unit ID)}), whether they come from a {@code @kind} tag or from a hidden alias type (which is
 * never shown by name because user code cannot use it).
 */
public final class KmrTypePresenter
{

	private static final int MAX_ALIAS_DEPTH = 16;

	private KmrTypePresenter()
	{
	}

	/** {@code Integer (unit ID)}, {@code array of Integer (unit ID)}, {@code TKMUnitType}, ... */
	@NotNull
	public static String typeText(@Nullable KmrPascalTypeSpec type)
	{
		return type == null ? "" : typeText(type.getTypeElement(), true);
	}

	/** Like {@link #typeText(KmrPascalTypeSpec)} without the kind words: valid PascalScript the user can write. */
	@NotNull
	public static String plainTypeText(@Nullable KmrPascalTypeSpec type)
	{
		return type == null ? "" : typeText(type.getTypeElement(), false);
	}

	@NotNull
	public static String typeText(@Nullable KmrPascalTypeElement type, boolean withKinds)
	{
		return typeText(type, withKinds, 0);
	}

	@NotNull
	private static String typeText(@Nullable KmrPascalTypeElement type, boolean withKinds, int depth)
	{
		if (type == null) {
			return "";
		}
		if (type instanceof KmrPascalTypeReference) {
			String name = ((KmrPascalTypeReference) type).getReferenceName();
			KmrPascalTypeDeclaration alias = hiddenAlias(type);
			if (alias == null || depth > MAX_ALIAS_DEPTH) {
				return name;
			}
			String inner = typeText(alias.getTypeSpec().getTypeElement(), withKinds, depth + 1);
			KmrKind kind = withKinds ? KmrKindInference.declaredKindOf(alias) : null;
			return kind == null ? inner : inner + " (" + kind.label + ")";
		}
		if (type instanceof KmrPascalArrayType) {
			KmrPascalArrayType array = (KmrPascalArrayType) type;
			KmrPascalIndexList indexList = array.getIndexList();
			String element = array.getTypeSpec() == null ? "const" : typeText(array.getTypeSpec().getTypeElement(), withKinds, depth + 1);
			return "array" + (indexList == null ? "" : " [" + normalize(indexList.getText()) + "]") + " of " + element;
		}
		if (type instanceof KmrPascalSetType) {
			return "set of " + typeText(PsiTreeUtil.getChildOfType(type, KmrPascalTypeElement.class), withKinds, depth + 1);
		}
		return normalize(type.getText());
	}

	/**
	 * {@code out aX: Integer (X coordinate)}: one parameter group as declared, with the type presented. A group whose
	 * names have different kinds ({@code X, Y: Integer}) is written per name.
	 */
	@NotNull
	public static String parameterText(@NotNull KmrPascalParameterDeclaration group, boolean withKinds)
	{
		KmrPascalArgumentModifier modifier = group.getArgumentModifier();
		String prefix = modifier == null ? "" : modifier.getText() + " ";
		List<KmrPascalParameterIdentifier> names = group.getParameterIdentifierList();
		KmrPascalTypeSpec type = group.getTypeSpec();
		String plain = type == null ? "" : typeText(type.getTypeElement(), withKinds, 0);
		List<String> labels = new ArrayList<>();
		for (KmrPascalParameterIdentifier name : names) {
			labels.add(withKinds ? kindLabel(name) : null);
		}
		if (labels.stream().distinct().count() <= 1) {
			StringBuilder sb = new StringBuilder(prefix);
			for (int i = 0; i < names.size(); i++) {
				sb.append(i > 0 ? ", " : "").append(names.get(i).getName());
			}
			return sb.append(type == null ? "" : ": " + withLabel(plain, labels.isEmpty() ? null : labels.get(0))).toString();
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < names.size(); i++) {
			sb.append(i > 0 ? "; " : "").append(prefix).append(names.get(i).getName()).append(": ").append(withLabel(plain, labels.get(i)));
		}
		return sb.toString();
	}

	/** {@code Integer (unit ID)} for a variable, parameter or field: its type plus the kind of its value. */
	@NotNull
	public static String typeText(@NotNull KmrPascalTypedIdentifier declaration, boolean withKinds)
	{
		String plain = typeText(declaration.getType() == null ? null : declaration.getType().getTypeElement(), withKinds, 0);
		return withKinds ? withLabel(plain, kindLabel(declaration)) : plain;
	}

	/** {@code : Integer (unit ID)} for a function: its return type plus the kind of the result. */
	@NotNull
	public static String returnTypeText(@NotNull KmrPascalCallable callable, boolean withKinds)
	{
		KmrPascalTypeSpec returnType = callable.getReturnType();
		String plain = returnType == null ? "" : typeText(returnType.getTypeElement(), withKinds, 0);
		if (!withKinds || returnType == null) {
			return plain;
		}
		KmrKind kind = KmrKindInference.resultKindOf(callable.getDeclaration(), arrayDepth(KmrTypeProvider.fromTypeSpec(returnType)));
		return withLabel(plain, kind == null ? null : kind.label);
	}

	/** The kind label of a declaration's (innermost) value, or null. */
	@Nullable
	private static String kindLabel(@NotNull KmrPascalTypedIdentifier declaration)
	{
		KmrKind kind = KmrKindInference.kindOfDeclaration(declaration, arrayDepth(KmrTypeProvider.declaredTypeOf(declaration)));
		return kind == null ? null : kind.label;
	}

	private static int arrayDepth(@NotNull KmrType type)
	{
		int depth = 0;
		while (type.is(KmrType.Kind.ARRAY) && type.element != null) {
			depth++;
			type = type.element;
		}
		return depth;
	}

	/** Appends the kind unless the text already carries one (from an alias type). */
	@NotNull
	private static String withLabel(@NotNull String text, @Nullable String label)
	{
		return label == null || text.endsWith(")") ? text : text + " (" + label + ")";
	}

	/**
	 * The declaration a type reference names when that is a hidden ({@code @hidden}) alias of another type
	 * (reference, array or set); records and enums are never expanded.
	 */
	@Nullable
	public static KmrPascalTypeDeclaration hiddenAlias(@NotNull KmrPascalTypeElement reference)
	{
		PsiElement target = KmrPascalTypeUtil.resolveSingle(reference);
		if (!(target instanceof KmrPascalTypeDeclaration)) {
			return null;
		}
		KmrPascalTypeDeclaration declaration = (KmrPascalTypeDeclaration) target;
		KmrPascalTypeElement aliased = declaration.getTypeSpec().getTypeElement();
		if (!(aliased instanceof KmrPascalTypeReference || aliased instanceof KmrPascalArrayType || aliased instanceof KmrPascalSetType)) {
			return null;
		}
		KmrPascalDocComment doc = KmrPascalDocComment.of(declaration);
		return doc != null && doc.hasTag(KmrPascalDocComment.TAG_HIDDEN) ? declaration : null;
	}

	@NotNull
	private static String normalize(@NotNull String text)
	{
		return text.replaceAll("\\s+", " ").trim();
	}

}
