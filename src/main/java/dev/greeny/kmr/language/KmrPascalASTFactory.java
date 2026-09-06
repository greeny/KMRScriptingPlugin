package dev.greeny.kmr.language;

import com.intellij.lang.ASTFactory;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.psi.KmrPascalCommentElement;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Creates {@link KmrPascalCommentElement}s for comment tokens so block comments above declarations can be rendered. */
public class KmrPascalASTFactory extends ASTFactory
{

	@Override
	public @Nullable LeafElement createLeaf(@NotNull IElementType type, @NotNull CharSequence text)
	{
		if (type == KmrPascalTypes.COMMENT_A || type == KmrPascalTypes.COMMENT_B) {
			return new KmrPascalCommentElement(type, text);
		}
		return null;
	}

}
