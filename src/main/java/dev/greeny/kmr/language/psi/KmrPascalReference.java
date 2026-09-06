package dev.greeny.kmr.language.psi;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import com.intellij.util.IncorrectOperationException;
import dev.greeny.kmr.language.resolve.KmrPascalResolver;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Reference from a {@link KmrPascalReferenceElement} to the declaration(s) with that name; case-insensitive. */
public class KmrPascalReference extends PsiPolyVariantReferenceBase<KmrPascalReferenceElement>
{

	private static final ResolveCache.PolyVariantResolver<KmrPascalReference> RESOLVER = (reference, incompleteCode) -> {
		List<PsiElement> targets = KmrPascalResolver.resolve(reference.getElement());
		return PsiElementResolveResult.createResults(targets);
	};

	public KmrPascalReference(@NotNull KmrPascalReferenceElement element)
	{
		super(element, rangeOfName(element));
	}

	private static TextRange rangeOfName(@NotNull KmrPascalReferenceElement element)
	{
		return element.getNameIdentifier().getTextRange().shiftLeft(element.getTextRange().getStartOffset());
	}

	@Override
	public ResolveResult @NotNull [] multiResolve(boolean incompleteCode)
	{
		return ResolveCache.getInstance(myElement.getProject()).resolveWithCaching(this, RESOLVER, false, incompleteCode);
	}

	@Override
	public boolean isReferenceTo(@NotNull PsiElement element)
	{
		if (!(element instanceof KmrPascalNamedElement)) {
			return false;
		}
		String name = ((KmrPascalNamedElement) element).getName();
		if (name == null || !name.equalsIgnoreCase(myElement.getReferenceName())) {
			return false;
		}
		return super.isReferenceTo(element);
	}

	@Override
	public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException
	{
		myElement.getNameIdentifier().replace(KmrPascalElementFactory.createIdentifier(myElement.getProject(), newElementName));
		return myElement;
	}

	@Override
	public Object @NotNull [] getVariants()
	{
		// completion is provided by editor/KmrPascalCompletionContributor
		return EMPTY_ARRAY;
	}

}
