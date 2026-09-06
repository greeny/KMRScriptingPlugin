package dev.greeny.kmr.language.resolve;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.types.KmrTypePresenter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Uniform view of something that can be called: a procedure/function declaration, or a variable/field/parameter
 * whose type is a procedural type (the form the API stubs use: {@code ShowMsg: procedure(aPlayer: Integer)}).
 */
public final class KmrPascalCallable
{

	private final PsiElement declaration;
	private final List<KmrPascalParameterDeclaration> parameters;
	@Nullable
	private final KmrPascalTypeSpec returnType;
	private final boolean function;

	private KmrPascalCallable(@NotNull PsiElement declaration, @NotNull List<KmrPascalParameterDeclaration> parameters, @Nullable KmrPascalTypeSpec returnType, boolean function)
	{
		this.declaration = declaration;
		this.parameters = parameters;
		this.returnType = returnType;
		this.function = function;
	}

	/** The callable view of a resolved element, or null if it cannot be called. */
	@Nullable
	public static KmrPascalCallable of(@Nullable PsiElement element)
	{
		if (element instanceof KmrPascalRoutineDeclaration) {
			KmrPascalRoutineDeclaration routine = (KmrPascalRoutineDeclaration) element;
			return new KmrPascalCallable(routine, routine.getParameters(), routine.getReturnType(), routine instanceof KmrPascalFunctionDeclaration);
		}
		if (element instanceof KmrPascalTypedIdentifier) {
			KmrPascalTypeElement type = KmrPascalTypeUtil.unwrap(KmrPascalTypeUtil.typeElementOf(((KmrPascalTypedIdentifier) element).getType()));
			if (type instanceof KmrPascalFunctionType) {
				KmrPascalFunctionType functionType = (KmrPascalFunctionType) type;
				return new KmrPascalCallable(element, functionType.getParameterDeclarationList(), functionType.getTypeSpec(), true);
			}
			if (type instanceof KmrPascalProcedureType) {
				return new KmrPascalCallable(element, ((KmrPascalProcedureType) type).getParameterDeclarationList(), null, false);
			}
		}
		return null;
	}

	/** The declaring element: routine declaration or typed identifier. */
	@NotNull
	public PsiElement getDeclaration()
	{
		return declaration;
	}

	@Nullable
	public String getName()
	{
		return declaration instanceof KmrPascalNamedElement ? ((KmrPascalNamedElement) declaration).getName() : null;
	}

	/** Parameter groups as declared ({@code a, b: Integer} is one group with two identifiers). */
	@NotNull
	public List<KmrPascalParameterDeclaration> getParameterDeclarations()
	{
		return parameters;
	}

	/** All parameter identifiers, flattened, in order. */
	@NotNull
	public List<KmrPascalParameterIdentifier> getParameterIdentifiers()
	{
		return parameters.stream().flatMap(group -> group.getParameterIdentifierList().stream()).toList();
	}

	public int getParameterCount()
	{
		return getParameterIdentifiers().size();
	}

	@Nullable
	public KmrPascalTypeSpec getReturnType()
	{
		return returnType;
	}

	public boolean isFunction()
	{
		return function;
	}

	/**
	 * {@code (aPlayer: Integer (hand index); const aText: AnsiString): Integer (unit ID)} style presentation: the
	 * kinds of the API stubs in words, never their hidden alias names.
	 */
	@NotNull
	public String getSignatureText()
	{
		return getSignatureText(true);
	}

	/** Without kinds ({@code withKinds = false}) the text is valid PascalScript for generated headers. */
	@NotNull
	public String getSignatureText(boolean withKinds)
	{
		StringBuilder sb = new StringBuilder();
		if (!parameters.isEmpty()) {
			sb.append('(');
			for (int i = 0; i < parameters.size(); i++) {
				if (i > 0) {
					sb.append("; ");
				}
				sb.append(KmrTypePresenter.parameterText(parameters.get(i), withKinds));
			}
			sb.append(')');
		}
		if (returnType != null) {
			sb.append(": ").append(KmrTypePresenter.returnTypeText(this, withKinds));
		}
		return sb.toString();
	}

	/** Whether the declaration is annotated {@code @deprecated}; the tag value is the message. */
	@Nullable
	public String getDeprecationMessage()
	{
		KmrPascalDocComment doc = KmrPascalDocComment.of(declaration);
		return doc == null || !doc.hasTag(KmrPascalDocComment.TAG_DEPRECATED) ? null : doc.getTagValue(KmrPascalDocComment.TAG_DEPRECATED);
	}

	@Nullable
	public static KmrPascalCallable at(@Nullable PsiElement place)
	{
		return of(PsiTreeUtil.getParentOfType(place, KmrPascalRoutineDeclaration.class, false));
	}

}
