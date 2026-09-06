package dev.greeny.kmr.language;

import com.intellij.lang.Language;

public class KmrPascalLanguage extends Language
{

	public static final KmrPascalLanguage INSTANCE = new KmrPascalLanguage();

	private KmrPascalLanguage()
	{
		super("KmrPascal");
	}

}
