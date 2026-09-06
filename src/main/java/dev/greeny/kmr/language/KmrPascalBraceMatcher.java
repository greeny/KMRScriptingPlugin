package dev.greeny.kmr.language;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class KmrPascalBraceMatcher implements PairedBraceMatcher
{

	@Override
	public BracePair @NotNull [] getPairs()
	{
		// END closes begin, case and record blocks. All three must be registered, otherwise the matcher
		// pairs the "end" of a case/record with the nearest enclosing "begin".
		return new BracePair[]{
			new BracePair(KmrPascalTypes.BEGIN, KmrPascalTypes.END, true),
			new BracePair(KmrPascalTypes.CASE, KmrPascalTypes.END, true),
			new BracePair(KmrPascalTypes.RECORD, KmrPascalTypes.END, true),
			new BracePair(KmrPascalTypes.REPEAT, KmrPascalTypes.UNTIL, true),
			new BracePair(KmrPascalTypes.DIRECTIVE_IFDEF, KmrPascalTypes.DIRECTIVE_ENDIF, true),
			new BracePair(KmrPascalTypes.DIRECTIVE_IFNDEF, KmrPascalTypes.DIRECTIVE_ENDIF, true),
			new BracePair(KmrPascalTypes.LBRACKET, KmrPascalTypes.RBRACKET, false),
			new BracePair(KmrPascalTypes.LSQUAREBRACKET, KmrPascalTypes.RSQUAREBRACKET, false),
		};
	}

	@Override
	public boolean isPairedBracesAllowedBeforeType(@NotNull IElementType lbraceType, @Nullable IElementType contextType)
	{
		return true;
	}

	@Override
	public int getCodeConstructStart(PsiFile file, int openingBraceOffset)
	{
		return openingBraceOffset;
	}

}
