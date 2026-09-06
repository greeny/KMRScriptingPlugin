package dev.greeny.kmr.language.resolve;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Declared-type lookups only (no inference; see {@code types/KmrTypeProvider} for that): enough to resolve {@code rec.field},
 * {@code arr[i].field} and {@code with rec do field}.
 */
public final class KmrPascalTypeUtil
{

	private static final int MAX_ALIAS_DEPTH = 16;

	private KmrPascalTypeUtil()
	{
	}

	/** The record type an expression is declared to have, following type aliases; null when unknown. */
	@Nullable
	public static KmrPascalRecordType recordTypeOf(@NotNull KmrPascalExpression expression)
	{
		KmrPascalTypeElement type = unwrap(typeElementOf(expression));
		return type instanceof KmrPascalRecordType ? (KmrPascalRecordType) type : null;
	}

	/** Fields of a record with the given name (case-insensitive). */
	@NotNull
	public static List<KmrPascalFieldIdentifier> findFields(@NotNull KmrPascalRecordType record, @NotNull String name)
	{
		return PsiTreeUtil.findChildrenOfType(record, KmrPascalFieldIdentifier.class).stream()
			.filter(field -> name.equalsIgnoreCase(field.getName()))
			.toList();
	}

	/** The declared type element of an expression, without following aliases. */
	@Nullable
	public static KmrPascalTypeElement typeElementOf(@NotNull KmrPascalExpression expression)
	{
		if (expression instanceof KmrPascalReferenceExpression) {
			return declaredTypeOf(resolveFirst(expression));
		}
		if (expression instanceof KmrPascalParenExpression) {
			KmrPascalExpression inner = ((KmrPascalParenExpression) expression).getExpression();
			return inner == null ? null : typeElementOf(inner);
		}
		if (expression instanceof KmrPascalCallExpression) {
			KmrPascalCallable callable = KmrPascalCallable.of(resolveFirst(((KmrPascalCallExpression) expression).getExpression()));
			return callable == null ? null : typeElementOf(callable.getReturnType());
		}
		if (expression instanceof KmrPascalIndexExpression) {
			List<KmrPascalExpression> children = ((KmrPascalIndexExpression) expression).getExpressionList();
			if (children.isEmpty()) {
				return null;
			}
			KmrPascalTypeElement type = unwrap(typeElementOf(children.get(0)));
			int indexes = children.size() - 1;
			while (indexes > 0 && type instanceof KmrPascalArrayType) {
				KmrPascalArrayType arrayType = (KmrPascalArrayType) type;
				KmrPascalIndexList indexList = arrayType.getIndexList();
				int dimensions = indexList == null ? 1 : Math.max(1, PsiTreeUtil.getChildrenOfTypeAsList(indexList, KmrPascalTypeElement.class).size());
				indexes -= dimensions;
				type = unwrap(typeElementOf(arrayType.getTypeSpec()));
			}
			return indexes == 0 ? type : null;
		}
		return null;
	}

	/** The concrete type element inside a {@code type-spec} node. */
	@Nullable
	public static KmrPascalTypeElement typeElementOf(@Nullable KmrPascalTypeSpec type)
	{
		return type == null ? null : type.getTypeElement();
	}

	/** The declared type of a named element: variables, parameters, fields, functions (result type), type declarations. */
	@Nullable
	public static KmrPascalTypeElement declaredTypeOf(@Nullable PsiElement target)
	{
		if (target instanceof KmrPascalVarIdentifier) {
			return typeElementOf(((KmrPascalVarIdentifier) target).getType());
		}
		if (target instanceof KmrPascalParameterIdentifier) {
			return typeElementOf(((KmrPascalParameterIdentifier) target).getType());
		}
		if (target instanceof KmrPascalFieldIdentifier) {
			return typeElementOf(((KmrPascalFieldIdentifier) target).getType());
		}
		if (target instanceof KmrPascalFunctionDeclaration) {
			return typeElementOf(((KmrPascalFunctionDeclaration) target).getTypeSpec());
		}
		if (target instanceof KmrPascalTypeDeclaration) {
			return typeElementOf(((KmrPascalTypeDeclaration) target).getTypeSpec());
		}
		return null;
	}

	/** Follows type-reference aliases until a structural type (or an unresolvable name) is reached. */
	@Nullable
	public static KmrPascalTypeElement unwrap(@Nullable KmrPascalTypeElement type)
	{
		int depth = 0;
		while (type instanceof KmrPascalTypeReference && depth++ < MAX_ALIAS_DEPTH) {
			PsiElement target = resolveFirst(type);
			if (!(target instanceof KmrPascalTypeDeclaration)) {
				return type;
			}
			type = declaredTypeOf(target);
		}
		return type;
	}

	@Nullable
	private static PsiElement resolveFirst(@Nullable PsiElement element)
	{
		return resolveSingle(element);
	}

	/** The single resolve target of an element's reference; null when unresolved or ambiguous. */
	@Nullable
	public static PsiElement resolveSingle(@Nullable PsiElement element)
	{
		if (element == null) {
			return null;
		}
		PsiReference reference = element.getReference();
		if (reference instanceof com.intellij.psi.PsiPolyVariantReference) {
			com.intellij.psi.ResolveResult[] results = ((com.intellij.psi.PsiPolyVariantReference) reference).multiResolve(false);
			return results.length == 1 ? results[0].getElement() : null;
		}
		return reference == null ? null : reference.resolve();
	}

}
