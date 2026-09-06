package dev.greeny.kmr.language.editor;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.util.IncorrectOperationException;
import dev.greeny.kmr.language.psi.KmrPascalElementFactory;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import dev.greeny.kmr.language.unit.KmrPascalIncludeResolver;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** {@code {$I name.script}} points at the included file. */
public class KmrPascalIncludeReference extends PsiReferenceBase<PsiComment>
{

	private final String includeArgument;

	public KmrPascalIncludeReference(@NotNull PsiComment element, @NotNull KmrPascalDirective directive)
	{
		super(element, directive.argumentRange == null ? TextRange.EMPTY_RANGE : directive.argumentRange, true);
		this.includeArgument = directive.argument;
	}

	@Override
	public @Nullable PsiElement resolve()
	{
		PsiFile containingFile = myElement.getContainingFile();
		VirtualFile from = containingFile.getVirtualFile();
		if (from == null) {
			return null;
		}
		VirtualFile entry = KmrPascalResolveContextService.getInstance(myElement.getProject()).getContextFor(containingFile).getVirtualFile();
		VirtualFile target = KmrPascalIncludeResolver.resolve(from, entry, includeArgument);
		return target == null ? null : PsiManager.getInstance(myElement.getProject()).findFile(target);
	}

	@Override
	public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException
	{
		// keep any directory part of the argument, replace the file name
		String normalized = includeArgument.replace('\\', '/');
		int slash = normalized.lastIndexOf('/');
		String newArgument = slash < 0 ? newElementName : includeArgument.substring(0, slash + 1) + newElementName;
		String text = myElement.getText();
		TextRange range = getRangeInElement();
		String newText = text.substring(0, range.getStartOffset()) + newArgument + text.substring(range.getEndOffset());
		PsiElement newComment = KmrPascalElementFactory.createFile(myElement.getProject(), newText).getFirstChild();
		return myElement.replace(newComment);
	}

	@Override
	public Object @NotNull [] getVariants()
	{
		return EMPTY_ARRAY;
	}

}
