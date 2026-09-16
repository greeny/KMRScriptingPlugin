package dev.greeny.kmr.language.editor;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.SimpleListCellRenderer;
import dev.greeny.kmr.language.KmrPascalFileType;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.unit.KmrPascalEntryPoints;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;
import java.util.function.Function;

/**
 * Shown on shared (included) scripts: which entry point the file is currently analysed in, with a switcher when
 * several entry points include it.
 */
public class KmrPascalResolveContextNotificationProvider implements EditorNotificationProvider
{

	@Override
	public @Nullable Function<? super @NotNull FileEditor, ? extends @Nullable JComponent> collectNotificationData(@NotNull Project project, @NotNull VirtualFile file)
	{
		if (DumbService.isDumb(project) || file.getFileType() != KmrPascalFileType.INSTANCE || KmrPascalEntryPoints.isEntryPoint(project, file)
			|| KmrPascalStubLibrary.stubVersionOf(file) != null) {
			return null;
		}
		KmrPascalResolveContextService service = KmrPascalResolveContextService.getInstance(project);
		List<VirtualFile> candidates = service.getCandidates(file);
		if (candidates.isEmpty() || (candidates.size() == 1 && candidates.get(0).equals(file))) {
			return null;
		}
		PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
		if (psiFile == null) {
			return null;
		}
		VirtualFile current = service.getContextFor(psiFile).getVirtualFile();
		return fileEditor -> {
			EditorNotificationPanel panel = new EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Info);
			String currentName = current == null ? "?" : relativePath(project, current);
			panel.setText(candidates.size() == 1
				? "Analyzed as part of " + currentName
				: "Analyzed as part of " + currentName + " (" + candidates.size() + " entry points include this file)");
			if (candidates.size() > 1) {
				panel.createActionLabel("Switch entry point", () -> JBPopupFactory.getInstance()
					.createPopupChooserBuilder(candidates)
					.setTitle("Analyze " + file.getName() + " as Part Of")
					.setRenderer(SimpleListCellRenderer.<VirtualFile>create((label, candidate, index) -> label.setText(relativePath(project, candidate))))
					.setItemChosenCallback(candidate -> service.setContext(file, candidate))
					.createPopup()
					.showUnderneathOf(panel));
			}
			return panel;
		};
	}

	@NotNull
	private static String relativePath(@NotNull Project project, @NotNull VirtualFile file)
	{
		VirtualFile base = ProjectUtil.guessProjectDir(project);
		String relative = base == null ? null : VfsUtilCore.getRelativePath(file, base);
		return relative == null ? file.getPath() : relative;
	}

}
