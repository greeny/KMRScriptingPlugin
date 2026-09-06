package dev.greeny.kmr.language.stubs;

import dev.greeny.kmr.language.psi.KmrPascalDocComment;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import dev.greeny.kmr.language.psi.KmrPascalNamedElement;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalProjectSettings;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Declarations the stubs contribute to name resolution, per version. User code sees everything except
 * {@code @hidden} declarations; code inside the stubs themselves sees everything of its own version.
 */
@Service(Service.Level.PROJECT)
public final class KmrPascalStubScope
{

	public static final class Declaration
	{
		public final KmrPascalNamedElement element;
		public final boolean hidden;

		Declaration(@NotNull KmrPascalNamedElement element, boolean hidden)
		{
			this.element = element;
			this.hidden = hidden;
		}
	}

	private static final Key<CachedValue<Map<String, List<Declaration>>>> KEY = Key.create("KmrPascalStubDeclarations");

	private final Project project;

	public KmrPascalStubScope(@NotNull Project project)
	{
		this.project = project;
	}

	@NotNull
	public static KmrPascalStubScope getInstance(@NotNull Project project)
	{
		return project.getService(KmrPascalStubScope.class);
	}

	/** The stub version used for user code in the given file (via its resolve context / entry point). */
	@NotNull
	public String versionFor(@NotNull PsiFile file)
	{
		String own = KmrPascalStubLibrary.stubVersionOf(file.getVirtualFile());
		if (own != null) {
			return own;
		}
		PsiFile entryPoint = KmrPascalResolveContextService.getInstance(project).getContextFor(file);
		return KmrPascalProjectSettings.getInstance(project).getStubVersionFor(entryPoint.getVirtualFile());
	}

	/** Stub declarations with the given name visible from the given place. */
	@NotNull
	public List<PsiElement> findDeclarations(@NotNull PsiFile place, @NotNull String name)
	{
		boolean insideStubs = KmrPascalStubLibrary.isStubFile(place);
		List<PsiElement> result = new ArrayList<>();
		for (Declaration declaration : declarations(versionFor(place)).getOrDefault(name.toLowerCase(), Collections.emptyList())) {
			if (insideStubs || !declaration.hidden) {
				result.add(declaration.element);
			}
		}
		return result;
	}

	/** All stub declarations of a version keyed by lower-cased name. */
	@NotNull
	public Map<String, List<Declaration>> declarations(@NotNull String version)
	{
		KmrPascalStubLibrary.StubSet set = KmrPascalStubLibrary.getInstance(project).getStubSet(version);
		List<PsiFile> files = set.scopePsiFiles(project);
		if (files.isEmpty()) {
			return Collections.emptyMap();
		}
		// cached on the first scope file of the set; stubs never change, but PSI may be dropped and re-created
		// the provider must not capture PSI, so it re-fetches the files from the (PSI-free) stub set
		return CachedValuesManager.getManager(project).getCachedValue(files.get(0), KEY,
			() -> CachedValueProvider.Result.create(collect(set.scopePsiFiles(project)), PsiModificationTracker.MODIFICATION_COUNT), false);
	}

	@NotNull
	private static Map<String, List<Declaration>> collect(@NotNull List<PsiFile> files)
	{
		Map<String, List<Declaration>> map = new HashMap<>();
		for (PsiFile file : files) {
			for (PsiElement child : file.getChildren()) {
				for (KmrPascalNamedElement element : KmrPascalCompilationUnit.topLevelDeclarations(child)) {
					String name = element.getName();
					if (name == null) {
						continue;
					}
					KmrPascalDocComment doc = KmrPascalDocComment.of(element);
					boolean hidden = doc != null && doc.hasTag(KmrPascalDocComment.TAG_HIDDEN);
					map.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(new Declaration(element, hidden));
				}
			}
		}
		return map;
	}

}
