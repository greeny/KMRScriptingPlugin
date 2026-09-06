package dev.greeny.kmr.language.editor;

import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import dev.greeny.kmr.language.KmrPascalLexerAdapter;
import dev.greeny.kmr.language.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class KmrPascalFindUsagesProvider implements FindUsagesProvider
{

	@Override
	public @Nullable WordsScanner getWordsScanner()
	{
		return new DefaultWordsScanner(new KmrPascalLexerAdapter(), KmrPascalTokenSets.IDENTIFIERS, KmrPascalTokenSets.COMMENTS, KmrPascalTokenSets.STRINGS);
	}

	@Override
	public boolean canFindUsagesFor(@NotNull PsiElement element)
	{
		return element instanceof KmrPascalNamedElement;
	}

	@Override
	public @Nullable String getHelpId(@NotNull PsiElement element)
	{
		return null;
	}

	@Override
	public @NotNull String getType(@NotNull PsiElement element)
	{
		if (element instanceof KmrPascalProcedureDeclaration) return "procedure";
		if (element instanceof KmrPascalFunctionDeclaration) return "function";
		if (element instanceof KmrPascalConstantDeclaration) return "constant";
		if (element instanceof KmrPascalTypeDeclaration) return "type";
		if (element instanceof KmrPascalVarIdentifier) return "variable";
		if (element instanceof KmrPascalParameterIdentifier) return "parameter";
		if (element instanceof KmrPascalFieldIdentifier) return "field";
		if (element instanceof KmrPascalEnumValue) return "enum value";
		return "symbol";
	}

	@Override
	public @NotNull String getDescriptiveName(@NotNull PsiElement element)
	{
		String name = element instanceof PsiNamedElement ? ((PsiNamedElement) element).getName() : null;
		return name == null ? "" : name;
	}

	@Override
	public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName)
	{
		return getDescriptiveName(element);
	}

}
