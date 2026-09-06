package dev.greeny.kmr.language.editor;

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.Processor;
import dev.greeny.kmr.language.psi.KmrPascalNamedElement;
import org.jetbrains.annotations.NotNull;

/**
 * Pascal identifiers are case-insensitive, but the platform's default reference search looks for the exact word.
 * This adds a case-insensitive word search for our named elements.
 */
public class KmrPascalReferencesSearcher extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>
{

	public KmrPascalReferencesSearcher()
	{
		super(true);
	}

	@Override
	public void processQuery(@NotNull ReferencesSearch.SearchParameters parameters, @NotNull Processor<? super PsiReference> consumer)
	{
		PsiElement target = parameters.getElementToSearch();
		if (!(target instanceof KmrPascalNamedElement)) {
			return;
		}
		String name = ((KmrPascalNamedElement) target).getName();
		if (name == null || name.isEmpty()) {
			return;
		}
		parameters.getOptimizer().searchWord(name, parameters.getEffectiveSearchScope(), UsageSearchContext.IN_CODE, false, target);
	}

}
