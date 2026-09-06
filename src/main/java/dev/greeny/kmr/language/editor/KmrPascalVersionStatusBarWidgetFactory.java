package dev.greeny.kmr.language.editor;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.impl.status.widget.StatusBarEditorBasedWidgetFactory;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

public class KmrPascalVersionStatusBarWidgetFactory extends StatusBarEditorBasedWidgetFactory
{

	@Override
	public @NotNull String getId()
	{
		return KmrPascalVersionStatusBarWidget.ID;
	}

	@Override
	public @Nls @NotNull String getDisplayName()
	{
		return "KMR Game Version";
	}

	@Override
	public boolean isAvailable(@NotNull Project project)
	{
		return true;
	}

	@Override
	public @NotNull StatusBarWidget createWidget(@NotNull Project project)
	{
		return new KmrPascalVersionStatusBarWidget(project);
	}

	@Override
	public void disposeWidget(@NotNull StatusBarWidget widget)
	{
		Disposer.dispose(widget);
	}

}
