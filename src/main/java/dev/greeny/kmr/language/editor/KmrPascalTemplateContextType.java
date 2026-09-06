package dev.greeny.kmr.language.editor;

import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.TemplateContextType;
import dev.greeny.kmr.language.KmrPascalLanguage;
import org.jetbrains.annotations.NotNull;

/** Live template context: any KMR PascalScript file. */
public class KmrPascalTemplateContextType extends TemplateContextType
{

	public KmrPascalTemplateContextType()
	{
		super("KMR PascalScript");
	}

	@Override
	public boolean isInContext(@NotNull TemplateActionContext context)
	{
		return context.getFile().getLanguage().isKindOf(KmrPascalLanguage.INSTANCE);
	}

}
