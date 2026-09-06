package dev.greeny.kmr.language.unit;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the argument of an include directive to a file.
 * <p>
 * ASSUMPTION (not yet verified against KM_Scripting.pas): the game resolves relative to the directory of the including file first, then
 * relative to the entry point's directory. Names are matched case-insensitively because the game runs on Windows.
 */
public final class KmrPascalIncludeResolver
{

	private KmrPascalIncludeResolver()
	{
	}

	@Nullable
	public static VirtualFile resolve(@NotNull VirtualFile includingFile, @Nullable VirtualFile entryPoint, @NotNull String includeArgument)
	{
		String[] segments = includeArgument.replace('\\', '/').split("/");
		VirtualFile found = findRelative(includingFile.getParent(), segments);
		if (found == null && entryPoint != null) {
			found = findRelative(entryPoint.getParent(), segments);
		}
		return found;
	}

	@Nullable
	private static VirtualFile findRelative(@Nullable VirtualFile directory, @NotNull String[] segments)
	{
		VirtualFile current = directory;
		for (String segment : segments) {
			if (current == null) {
				return null;
			}
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..")) {
				current = current.getParent();
				continue;
			}
			current = findChildIgnoreCase(current, segment);
		}
		return current == null || current.isDirectory() ? null : current;
	}

	@Nullable
	private static VirtualFile findChildIgnoreCase(@NotNull VirtualFile directory, @NotNull String name)
	{
		VirtualFile exact = directory.findChild(name);
		if (exact != null) {
			return exact;
		}
		for (VirtualFile child : directory.getChildren()) {
			if (child.getName().equalsIgnoreCase(name)) {
				return child;
			}
		}
		return null;
	}

}
