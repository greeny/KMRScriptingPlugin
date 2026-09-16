package dev.greeny.kmr.language.editor;

import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.RefactoringActionHandler;
import dev.greeny.kmr.language.psi.KmrPascalNamedElement;
import dev.greeny.kmr.language.refactoring.KmrPascalExtractRoutineHandler;
import dev.greeny.kmr.language.refactoring.KmrPascalIntroduceConstantHandler;
import dev.greeny.kmr.language.refactoring.KmrPascalIntroduceVariableHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class KmrPascalRefactoringSupportProvider extends RefactoringSupportProvider
{

	@Override
	public boolean isMemberInplaceRenameAvailable(@NotNull PsiElement element, @Nullable PsiElement context)
	{
		return element instanceof KmrPascalNamedElement;
	}

	@Override
	public @Nullable RefactoringActionHandler getIntroduceVariableHandler()
	{
		return new KmrPascalIntroduceVariableHandler();
	}

	@Override
	public @Nullable RefactoringActionHandler getIntroduceConstantHandler()
	{
		return new KmrPascalIntroduceConstantHandler();
	}

	@Override
	public @Nullable RefactoringActionHandler getExtractMethodHandler()
	{
		return new KmrPascalExtractRoutineHandler();
	}

}
