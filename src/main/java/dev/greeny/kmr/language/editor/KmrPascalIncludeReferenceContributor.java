package dev.greeny.kmr.language.editor;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import org.jetbrains.annotations.NotNull;

public class KmrPascalIncludeReferenceContributor extends PsiReferenceContributor
{

	@Override
	public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar)
	{
		registrar.registerReferenceProvider(PlatformPatterns.psiComment().withElementType(KmrPascalTypes.DIRECTIVE_INCLUDE), new PsiReferenceProvider()
		{
			@Override
			public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context)
			{
				KmrPascalDirective directive = KmrPascalDirective.of(element);
				if (directive == null || directive.argumentRange == null) {
					return PsiReference.EMPTY_ARRAY;
				}
				return new PsiReference[]{new KmrPascalIncludeReference((PsiComment) element, directive)};
			}
		});
		registrar.registerReferenceProvider(PlatformPatterns.psiComment().withElementType(KmrPascalTypes.DIRECTIVE_EVENT), new PsiReferenceProvider()
		{
			@Override
			public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context)
			{
				KmrPascalDirective directive = KmrPascalDirective.of(element);
				if (directive == null || directive.argumentRange == null) {
					return PsiReference.EMPTY_ARRAY;
				}
				return KmrPascalEventDirectiveReference.create((PsiComment) element, directive);
			}
		});
	}

}
