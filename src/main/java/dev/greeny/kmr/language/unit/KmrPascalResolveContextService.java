package dev.greeny.kmr.language.unit;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.ui.EditorNotifications;
import dev.greeny.kmr.language.psi.KmrPascalFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Decides in which compilation unit a file is analysed: an entry point is its own context, a shared file uses the
 * entry point the user picked (stored in {@link KmrPascalProjectSettings}), otherwise the first entry point that
 * includes it, otherwise itself.
 */
@Service(Service.Level.PROJECT)
public final class KmrPascalResolveContextService
{

	private static final Key<CachedValue<PsiFile>> CONTEXT_KEY = Key.create("KmrPascalResolveContext");

	private final Project project;

	public KmrPascalResolveContextService(@NotNull Project project)
	{
		this.project = project;
	}

	@NotNull
	public static KmrPascalResolveContextService getInstance(@NotNull Project project)
	{
		return project.getService(KmrPascalResolveContextService.class);
	}

	/** The entry point file whose unit is used to analyse the given file. */
	@NotNull
	public PsiFile getContextFor(@NotNull PsiFile file)
	{
		if (file.getVirtualFile() == null) {
			return file;
		}
		return CachedValuesManager.getManager(project).getCachedValue(file, CONTEXT_KEY, () ->
				CachedValueProvider.Result.create(computeContext(file), PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
					DumbService.getInstance(project).getModificationTracker()),
			false);
	}

	@NotNull
	public KmrPascalCompilationUnit getUnitFor(@NotNull PsiFile file)
	{
		return KmrPascalCompilationUnit.of(getContextFor(file));
	}

	/** Entry points that can serve as context for the file, sorted by path. */
	@NotNull
	public List<VirtualFile> getCandidates(@NotNull VirtualFile file)
	{
		return KmrPascalEntryPoints.findEntryPointsFor(project, file);
	}

	public void setContext(@NotNull VirtualFile file, @NotNull VirtualFile entryPoint)
	{
		KmrPascalProjectSettings.getInstance(project).setResolveContext(file, entryPoint);
		PsiManager.getInstance(project).dropPsiCaches();
		EditorNotifications.getInstance(project).updateAllNotifications();
		DaemonCodeAnalyzer.getInstance(project).restart();
	}

	@NotNull
	private PsiFile computeContext(@NotNull PsiFile file)
	{
		VirtualFile virtualFile = file.getVirtualFile();
		List<VirtualFile> candidates = getCandidates(virtualFile);
		VirtualFile chosen = null;
		String chosenUrl = KmrPascalProjectSettings.getInstance(project).getResolveContextUrl(virtualFile);
		if (chosenUrl != null) {
			for (VirtualFile candidate : candidates) {
				if (candidate.getUrl().equals(chosenUrl)) {
					chosen = candidate;
				}
			}
		}
		if (chosen == null && !candidates.isEmpty()) {
			chosen = candidates.get(0);
		}
		if (chosen == null || chosen.equals(virtualFile)) {
			return file;
		}
		PsiFile psiFile = PsiManager.getInstance(project).findFile(chosen);
		return psiFile instanceof KmrPascalFile ? psiFile : file;
	}

}
