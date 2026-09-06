package dev.greeny.kmr.language.editor;

import com.intellij.navigation.ChooseByNameContributorEx;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.Processor;
import com.intellij.util.indexing.FindSymbolParameters;
import com.intellij.util.indexing.IdFilter;
import dev.greeny.kmr.language.KmrPascalFileType;
import dev.greeny.kmr.language.psi.KmrPascalNamedElement;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Go to Symbol (Ctrl+Alt+Shift+N): top-level declarations of all scripts in the project. Scripts are few, so no index. */
public class KmrPascalGotoSymbolContributor implements ChooseByNameContributorEx
{

	@Override
	public void processNames(@NotNull Processor<? super String> processor, @NotNull GlobalSearchScope scope, @Nullable IdFilter filter)
	{
		Project project = scope.getProject();
		if (project == null) {
			return;
		}
		for (KmrPascalNamedElement declaration : declarations(project, scope)) {
			if (declaration.getName() != null && !processor.process(declaration.getName())) {
				return;
			}
		}
	}

	@Override
	public void processElementsWithName(@NotNull String name, @NotNull Processor<? super NavigationItem> processor, @NotNull FindSymbolParameters parameters)
	{
		for (KmrPascalNamedElement declaration : declarations(parameters.getProject(), parameters.getSearchScope())) {
			if (name.equalsIgnoreCase(declaration.getName()) && declaration instanceof NavigationItem && !processor.process((NavigationItem) declaration)) {
				return;
			}
		}
	}

	@NotNull
	private static java.util.List<KmrPascalNamedElement> declarations(@NotNull Project project, @NotNull GlobalSearchScope scope)
	{
		java.util.List<KmrPascalNamedElement> result = new java.util.ArrayList<>();
		PsiManager psiManager = PsiManager.getInstance(project);
		for (VirtualFile file : FileTypeIndex.getFiles(KmrPascalFileType.INSTANCE, scope)) {
			PsiFile psiFile = psiManager.findFile(file);
			if (psiFile == null) {
				continue;
			}
			for (PsiElement child : psiFile.getChildren()) {
				result.addAll(KmrPascalCompilationUnit.topLevelDeclarations(child));
			}
		}
		return result;
	}

}
