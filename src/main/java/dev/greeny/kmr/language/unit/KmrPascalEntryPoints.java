package dev.greeny.kmr.language.unit;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import dev.greeny.kmr.language.KmrPascalFileType;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Entry points are the scripts the game loads directly: a {@code <Map>.script} next to a {@code <Map>.dat} or
 * {@code <Map>.map}, or any script the user marked manually. Everything else only exists as an include.
 */
public final class KmrPascalEntryPoints
{

	private static final Set<String> MAP_EXTENSIONS = Set.of("dat", "map");

	private KmrPascalEntryPoints()
	{
	}

	public static boolean isScript(@NotNull VirtualFile file)
	{
		return !file.isDirectory() && file.getFileType() == KmrPascalFileType.INSTANCE;
	}

	public static boolean isEntryPoint(@NotNull Project project, @NotNull VirtualFile file)
	{
		return isScript(file) && (KmrPascalProjectSettings.getInstance(project).isManualEntryPoint(file) || hasSiblingMapFile(file));
	}

	public static boolean hasSiblingMapFile(@NotNull VirtualFile file)
	{
		VirtualFile parent = file.getParent();
		if (parent == null) {
			return false;
		}
		String baseName = file.getNameWithoutExtension();
		for (VirtualFile sibling : parent.getChildren()) {
			if (!sibling.isDirectory()
				&& MAP_EXTENSIONS.contains(sibling.getExtension() == null ? "" : sibling.getExtension().toLowerCase())
				&& sibling.getNameWithoutExtension().equalsIgnoreCase(baseName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The entry points whose units (transitively) include the file, sorted by path. An entry point returns itself.
	 * A file nobody includes returns itself, so standalone files still get analysed. If the file is included but
	 * none of the roots is a recognised entry point, the roots are returned instead.
	 */
	@NotNull
	public static List<VirtualFile> findEntryPointsFor(@NotNull Project project, @NotNull VirtualFile file)
	{
		if (isEntryPoint(project, file)) {
			return List.of(file);
		}
		Set<VirtualFile> visited = new HashSet<>();
		List<VirtualFile> entryPoints = new ArrayList<>();
		List<VirtualFile> roots = new ArrayList<>();
		Deque<VirtualFile> queue = new ArrayDeque<>();
		queue.add(file);
		visited.add(file);
		while (!queue.isEmpty()) {
			VirtualFile current = queue.poll();
			Collection<VirtualFile> includers = includersOf(project, current);
			if (includers.isEmpty()) {
				if (!current.equals(file)) {
					roots.add(current);
				}
				continue;
			}
			for (VirtualFile includer : includers) {
				if (isEntryPoint(project, includer)) {
					if (!entryPoints.contains(includer)) {
						entryPoints.add(includer);
					}
				} else if (visited.add(includer)) {
					queue.add(includer);
				}
			}
		}
		List<VirtualFile> result = entryPoints.isEmpty() ? roots : entryPoints;
		if (result.isEmpty()) {
			return List.of(file);
		}
		result.sort(Comparator.comparing(VirtualFile::getPath));
		return result;
	}

	/** Files whose include directives actually resolve to the given file. */
	@NotNull
	public static Collection<VirtualFile> includersOf(@NotNull Project project, @NotNull VirtualFile file)
	{
		List<VirtualFile> result = new ArrayList<>();
		PsiManager psiManager = PsiManager.getInstance(project);
		for (VirtualFile candidate : KmrPascalIncludeIndex.filesIncluding(project, file.getName())) {
			if (candidate.equals(file)) {
				continue;
			}
			PsiFile psiFile = psiManager.findFile(candidate);
			if (psiFile == null) {
				continue;
			}
			for (KmrPascalDirective directive : KmrPascalDirective.collect(psiFile)) {
				if (directive.kind == KmrPascalDirective.Kind.INCLUDE
					&& file.equals(KmrPascalIncludeResolver.resolve(candidate, null, directive.argument))) {
					result.add(candidate);
					break;
				}
			}
		}
		return result;
	}

}
