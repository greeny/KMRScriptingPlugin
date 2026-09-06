package dev.greeny.kmr.language.editor;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.impl.status.EditorBasedStatusBarPopup;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import dev.greeny.kmr.language.KmrPascalFileType;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.stubs.KmrPascalStubScope;
import dev.greeny.kmr.language.unit.KmrPascalProjectSettings;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Status bar item "KMR: r16020" shown while a script is open (like PhpStorm's PHP language level): displays the
 * game version whose API stubs are used for the current file and switches the project-wide default on click.
 */
public class KmrPascalVersionStatusBarWidget extends EditorBasedStatusBarPopup
{

	public static final String ID = "KmrPascalGameVersion";

	public KmrPascalVersionStatusBarWidget(@NotNull Project project)
	{
		super(project, false);
	}

	@Override
	public @NotNull String ID()
	{
		return ID;
	}

	@Override
	protected @NotNull WidgetState getWidgetState(@Nullable VirtualFile file)
	{
		if (file == null || file.getFileType() != KmrPascalFileType.INSTANCE) {
			return WidgetState.HIDDEN;
		}
		Project project = getProject();
		if (DumbService.isDumb(project)) {
			// the entry point (and thus a per-map override) needs the include index; show the default until indexing ends
			String defaultVersion = KmrPascalProjectSettings.getInstance(project).getDefaultStubVersion();
			return new WidgetState("KaM Remake version whose scripting API is used for code insight (indexing in progress).", widgetText(defaultVersion), true);
		}
		String version = effectiveVersion(project, file);
		String defaultVersion = KmrPascalProjectSettings.getInstance(project).getDefaultStubVersion();
		String tooltip = version.equals(defaultVersion)
			? "KaM Remake version whose scripting API is used for code insight. Click to change."
			: "KaM Remake version set for this map's entry point (project default: " + defaultVersion + "). Click to change the default.";
		return new WidgetState(tooltip, widgetText(version), true);
	}

	@Override
	protected @Nullable ListPopup createPopup(@NotNull DataContext context)
	{
		DefaultActionGroup group = new DefaultActionGroup();
		Project project = getProject();
		String current = KmrPascalProjectSettings.getInstance(project).getDefaultStubVersion();
		for (String version : KmrPascalStubLibrary.VERSIONS) {
			group.add(new AnAction(version.equals(current) ? version + " (current)" : version)
			{
				@Override
				public void actionPerformed(@NotNull AnActionEvent e)
				{
					setDefaultVersion(project, version);
					KmrPascalVersionStatusBarWidget.this.update();
				}

				@Override
				public @NotNull ActionUpdateThread getActionUpdateThread()
				{
					return ActionUpdateThread.BGT;
				}
			});
		}
		return JBPopupFactory.getInstance().createActionGroupPopup("KaM Remake API Version", group, context, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);
	}

	@Override
	protected void registerCustomListeners()
	{
		// called from the superclass constructor, so no field of this class is initialised yet: only use getProject()
		getProject().getMessageBus().connect(this).subscribe(DumbService.DUMB_MODE, new DumbService.DumbModeListener()
		{
			@Override
			public void exitDumbMode()
			{
				update();
			}
		});
	}

	@Override
	protected @NotNull StatusBarWidget createInstance(@NotNull Project project)
	{
		return new KmrPascalVersionStatusBarWidget(project);
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** The stub version used for the given file: its entry point's override, else the project default. */
	@NotNull
	public static String effectiveVersion(@NotNull Project project, @NotNull VirtualFile file)
	{
		PsiFile psiFile = DumbService.isDumb(project) ? null : PsiManager.getInstance(project).findFile(file);
		if (psiFile == null) {
			return KmrPascalProjectSettings.getInstance(project).getDefaultStubVersion();
		}
		return KmrPascalStubScope.getInstance(project).versionFor(psiFile);
	}

	@NotNull
	public static String widgetText(@NotNull String version)
	{
		return "KMR: " + version;
	}

	/** Changes the project-wide default and re-analyses open files. */
	public static void setDefaultVersion(@NotNull Project project, @NotNull String version)
	{
		KmrPascalProjectSettings.getInstance(project).setDefaultStubVersion(version);
		PsiManager.getInstance(project).dropPsiCaches();
		DaemonCodeAnalyzer.getInstance(project).restart();
		// entry points may resolve differently now
		KmrPascalResolveContextService.getInstance(project);
	}

}
