package dev.greeny.kmr.language;

import com.intellij.lexer.FlexAdapter;

public class KmrPascalLexerAdapter extends FlexAdapter
{

	public KmrPascalLexerAdapter()
	{
		super(new KmrPascalLexer(null));
	}

}
