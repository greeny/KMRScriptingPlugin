package dev.greeny.kmr.language.editor;

import com.intellij.codeInsight.hints.InlayInfo;
import com.intellij.codeInsight.hints.InlayParameterHintsProvider;
import com.intellij.codeInsight.hints.HintInfo;
import com.intellij.codeInsight.hints.Option;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parameter name hints inside calls: {@code Actions.ShowMsg(aHand: 0, aText: 'Hi')}.
 * <p>
 * A hint is left out when it would say nothing new: the argument is (a call of) something with the same name as the
 * parameter ({@code aPlayer} or {@code Player} for {@code aPlayer}, {@code States.UnitType(u)} for {@code aUnitType}),
 * or the routine has one parameter whose name is already in the routine's name ({@code PlayerDefeat(0)}).
 * Calls with the wrong number of arguments get no hints; the argument-count inspection reports those.
 */
public class KmrPascalInlayParameterHintsProvider implements InlayParameterHintsProvider
{

	@Override
	public @NotNull List<InlayInfo> getParameterHints(@NotNull PsiElement element)
	{
		if (!(element instanceof KmrPascalCallExpression)) {
			return Collections.emptyList();
		}
		KmrPascalCallExpression call = (KmrPascalCallExpression) element;
		KmrPascalFunctionArgumentList argumentList = call.getFunctionArgumentList();
		if (argumentList == null) {
			return Collections.emptyList();
		}
		List<KmrPascalExpression> arguments = argumentList.getExpressionList();
		PsiFile file = call.getContainingFile();
		if (arguments.isEmpty() || file == null || KmrPascalStubLibrary.isStubFile(file)) {
			return Collections.emptyList();
		}
		KmrPascalCallable callable = KmrPascalCallable.of(resolveSingle(call.getExpression()));
		if (callable == null) {
			return Collections.emptyList();
		}
		List<KmrPascalParameterIdentifier> parameters = callable.getParameterIdentifiers();
		if (parameters.size() != arguments.size()) {
			return Collections.emptyList();
		}
		String calleeName = callable.getName();
		List<InlayInfo> hints = new ArrayList<>();
		for (int i = 0; i < arguments.size(); i++) {
			String parameterName = parameters.get(i).getName();
			if (parameterName == null || isRedundant(parameterName, arguments.get(i), calleeName, parameters.size())) {
				continue;
			}
			hints.add(new InlayInfo(parameterName, arguments.get(i).getTextRange().getStartOffset()));
		}
		return hints;
	}

	@Override
	public @Nullable HintInfo getHintInfo(@NotNull PsiElement element)
	{
		if (!(element instanceof KmrPascalCallExpression)) {
			return null;
		}
		KmrPascalCallable callable = KmrPascalCallable.of(resolveSingle(((KmrPascalCallExpression) element).getExpression()));
		if (callable == null || callable.getName() == null) {
			return null;
		}
		PsiElement declaration = callable.getDeclaration();
		String owner = declaration instanceof KmrPascalFieldIdentifier ? KmrPascalDocumentationProvider.ownerName((KmrPascalFieldIdentifier) declaration) : null;
		String qualifiedName = owner == null ? callable.getName() : owner + "." + callable.getName();
		List<String> names = new ArrayList<>();
		for (KmrPascalParameterIdentifier parameter : callable.getParameterIdentifiers()) {
			names.add(String.valueOf(parameter.getName()));
		}
		return new HintInfo.MethodInfo(qualifiedName, names);
	}

	@Override
	public @NotNull Set<String> getDefaultBlackList()
	{
		return Collections.emptySet();
	}

	@Override
	public @NotNull List<Option> getSupportedOptions()
	{
		return Collections.emptyList();
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** True when the hint would repeat what the argument or the routine name already says. */
	static boolean isRedundant(@NotNull String parameterName, @NotNull KmrPascalExpression argument, @Nullable String calleeName, int parameterCount)
	{
		String parameter = normalize(parameterName);
		if (parameterCount == 1 && calleeName != null && calleeName.toLowerCase(Locale.ROOT).contains(parameter)) {
			return true;
		}
		String argumentName = nameOf(argument);
		if (argumentName == null) {
			return false;
		}
		String name = normalize(argumentName);
		return name.equals(parameter) || (parameter.length() >= 3 && name.endsWith(parameter));
	}

	/** The identifier an argument is named by: {@code X}, {@code Rec.X}, {@code aRec.X}, {@code States.UnitType(u)} give X, X, X, UnitType. */
	@Nullable
	private static String nameOf(@NotNull KmrPascalExpression argument)
	{
		KmrPascalExpression expression = argument;
		while (expression instanceof KmrPascalCallExpression) {
			expression = ((KmrPascalCallExpression) expression).getExpression();
		}
		return expression instanceof KmrPascalReferenceExpression ? ((KmrPascalReferenceExpression) expression).getReferenceName() : null;
	}

	/** Lower-cased, without the API's {@code a} prefix ({@code aPlayer} and {@code Player} compare equal). */
	@NotNull
	static String normalize(@NotNull String identifier)
	{
		if (identifier.length() > 1 && identifier.charAt(0) == 'a' && Character.isUpperCase(identifier.charAt(1))) {
			identifier = identifier.substring(1);
		}
		return identifier.toLowerCase(Locale.ROOT);
	}

	@Nullable
	private static PsiElement resolveSingle(@NotNull PsiElement element)
	{
		PsiReference reference = element.getReference();
		if (reference instanceof PsiPolyVariantReference) {
			ResolveResult[] results = ((PsiPolyVariantReference) reference).multiResolve(false);
			return results.length == 1 ? results[0].getElement() : null;
		}
		return reference == null ? null : reference.resolve();
	}

}
