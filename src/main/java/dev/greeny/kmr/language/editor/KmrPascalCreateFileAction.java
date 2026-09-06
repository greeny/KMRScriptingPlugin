package dev.greeny.kmr.language.editor;

import com.intellij.ide.actions.CreateFileFromTemplateAction;
import com.intellij.ide.actions.CreateFileFromTemplateDialog;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDirectory;
import dev.greeny.kmr.language.KmrPascalIcons;
import org.jetbrains.annotations.NotNull;

/** New | KMR Script: an empty .script file, or one wrapped in an include guard for shared scripts. */
public class KmrPascalCreateFileAction extends CreateFileFromTemplateAction
{

	public static final String TEMPLATE = "KMR Script";
	public static final String GUARDED_TEMPLATE = "KMR Script with Include Guard";

	public KmrPascalCreateFileAction()
	{
		super("KMR Script", "Knights and Merchants Remake script", KmrPascalIcons.FILE);
	}

	@Override
	protected void buildDialog(@NotNull Project project, @NotNull PsiDirectory directory, CreateFileFromTemplateDialog.@NotNull Builder builder)
	{
		builder.setTitle("New KMR Script")
			.addKind("Script", KmrPascalIcons.FILE, TEMPLATE)
			.addKind("Script with include guard", KmrPascalIcons.FILE, GUARDED_TEMPLATE);
	}

	@Override
	protected String getActionName(PsiDirectory directory, @NotNull String newName, String templateName)
	{
		return "Create KMR Script " + newName;
	}

}
