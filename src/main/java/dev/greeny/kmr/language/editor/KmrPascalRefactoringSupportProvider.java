package dev.greeny.kmr.language.editor;

import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.psi.PsiElement;
import dev.greeny.kmr.language.psi.KmrPascalNamedElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class KmrPascalRefactoringSupportProvider extends RefactoringSupportProvider
{

	@Override
	public boolean isMemberInplaceRenameAvailable(@NotNull PsiElement element, @Nullable PsiElement context)
	{
		return element instanceof KmrPascalNamedElement;
	}

}
