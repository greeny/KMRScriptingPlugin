package dev.greeny.kmr.language.psi;

import com.intellij.psi.PsiDocCommentBase;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.impl.source.tree.PsiCommentImpl;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A comment token that can act as a documentation comment: when a block comment ({@code { ... }} or {@code (* ... *)})
 * stands directly above a declaration, {@link #getOwner()} is that declaration and the IDE can render the comment
 * (Reader Mode, "Render documentation comments"). Line comments and directives are ordinary comments.
 */
public class KmrPascalCommentElement extends PsiCommentImpl implements PsiDocCommentBase
{

	public KmrPascalCommentElement(@NotNull IElementType type, @NotNull CharSequence text)
	{
		super(type, text);
	}

	/** True for {@code { ... }} and {@code (* ... *)} comments (not directives, not {@code //} lines). */
	public boolean isBlockComment()
	{
		IElementType type = getTokenType();
		return type == KmrPascalTypes.COMMENT_B || type == KmrPascalTypes.COMMENT_A && getText().startsWith("{");
	}

	/** The declaration this block comment documents (directly below it, at most one line break away), or null. */
	@Override
	public @Nullable PsiElement getOwner()
	{
		if (!isBlockComment()) {
			return null;
		}
		PsiElement next = getNextSibling();
		while (next instanceof PsiWhiteSpace) {
			if (next.getText().chars().filter(c -> c == '\n').count() > 1) {
				return null;
			}
			next = next.getNextSibling();
		}
		return isDeclaration(next) ? next : null;
	}

	private static boolean isDeclaration(@Nullable PsiElement element)
	{
		return element instanceof KmrPascalNamedElement || element instanceof KmrPascalVarDeclaration || element instanceof KmrPascalFieldDeclaration
			|| element instanceof KmrPascalVarDeclarations || element instanceof KmrPascalConstantDeclarations || element instanceof KmrPascalTypeDeclarations
			|| element instanceof KmrPascalParameterDeclaration;
	}

}
