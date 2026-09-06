package dev.greeny.kmr.language.stubs;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.LightVirtualFile;
import dev.greeny.kmr.language.KmrPascalFileType;
import dev.greeny.kmr.language.psi.KmrPascalFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bundled API stubs: one set of plain PascalScript files per supported game version, exposed as read-only
 * in-memory files so declarations can be navigated to like ordinary code.
 * <p>
 * {@code Actions/States/Utils/Types} form the <em>scope files</em> (their declarations are visible to user code,
 * except {@code @hidden} ones); {@code Events} is read only by {@link KmrPascalEvents}. The version-independent
 * {@code system/System.script} (PascalScript's own standard routines) joins the scope files of every version.
 * <p>
 * A project-level service: the platform allows a light (in-memory) file to have PSI in one project only, so every
 * open project gets its own copies of the stub files.
 */
@Service(Service.Level.PROJECT)
public final class KmrPascalStubLibrary
{

	/** The newest supported game version; also what a stored legacy "latest" setting maps to. */
	public static final String LATEST = "r16020";
	private static final String LEGACY_LATEST = "latest";
	/** Supported stub sets, newest first. */
	public static final List<String> VERSIONS = List.of(LATEST, "r6720");

	private static final String[] SCOPE_FILES = {"Types.script", "Actions.script", "States.script", "Utils.script"};
	private static final String EVENTS_FILE = "Events.script";
	private static final String SYSTEM_FILE = "System.script";
	private static final String SYSTEM_DIRECTORY = "system";

	/** Set on every stub virtual file: the version it belongs to. */
	public static final Key<String> STUB_VERSION = Key.create("KmrPascalStubVersion");
	/** Set on the events stub virtual file. */
	public static final Key<Boolean> EVENTS_STUB = Key.create("KmrPascalEventsStub");
	/** Set on the System stub virtual file (PascalScript standard library, shared by all versions). */
	public static final Key<Boolean> SYSTEM_STUB = Key.create("KmrPascalSystemStub");

	public static final class StubSet
	{
		public final String version;
		public final List<VirtualFile> scopeFiles;
		@Nullable
		public final VirtualFile eventsFile;

		StubSet(@NotNull String version, @NotNull List<VirtualFile> scopeFiles, @Nullable VirtualFile eventsFile)
		{
			this.version = version;
			this.scopeFiles = Collections.unmodifiableList(scopeFiles);
			this.eventsFile = eventsFile;
		}

		@NotNull
		public List<PsiFile> scopePsiFiles(@NotNull Project project)
		{
			List<PsiFile> result = new ArrayList<>();
			PsiManager manager = PsiManager.getInstance(project);
			for (VirtualFile file : scopeFiles) {
				PsiFile psiFile = manager.findFile(file);
				if (psiFile instanceof KmrPascalFile) {
					result.add(psiFile);
				}
			}
			return result;
		}

		@Nullable
		public PsiFile eventsPsiFile(@NotNull Project project)
		{
			PsiFile psiFile = eventsFile == null ? null : PsiManager.getInstance(project).findFile(eventsFile);
			return psiFile instanceof KmrPascalFile ? psiFile : null;
		}
	}

	private final Map<String, StubSet> sets = new ConcurrentHashMap<>();

	@NotNull
	public static KmrPascalStubLibrary getInstance(@NotNull Project project)
	{
		return project.getService(KmrPascalStubLibrary.class);
	}

	@NotNull
	public StubSet getStubSet(@NotNull String version)
	{
		return sets.computeIfAbsent(normalize(version), this::load);
	}

	/** A supported version name: unknown names and the legacy "latest" become {@link #LATEST}. */
	@NotNull
	public static String normalize(@Nullable String version)
	{
		return version != null && VERSIONS.contains(version) && !LEGACY_LATEST.equals(version) ? version : LATEST;
	}

	/** The stub version a file belongs to, or null for ordinary files. */
	@Nullable
	public static String stubVersionOf(@Nullable VirtualFile file)
	{
		return file == null ? null : file.getUserData(STUB_VERSION);
	}

	public static boolean isStubFile(@Nullable PsiFile file)
	{
		return file != null && stubVersionOf(file.getVirtualFile()) != null;
	}

	public static boolean isEventsStubFile(@Nullable PsiFile file)
	{
		return file != null && file.getVirtualFile() != null && Boolean.TRUE.equals(file.getVirtualFile().getUserData(EVENTS_STUB));
	}

	/** Whether the file is the PascalScript standard library stub (as opposed to the KaM Remake API). */
	public static boolean isSystemStubFile(@Nullable PsiFile file)
	{
		return file != null && file.getVirtualFile() != null && Boolean.TRUE.equals(file.getVirtualFile().getUserData(SYSTEM_STUB));
	}

	@NotNull
	private StubSet load(@NotNull String version)
	{
		List<VirtualFile> scopeFiles = new ArrayList<>();
		for (String name : SCOPE_FILES) {
			VirtualFile file = createStubFile(version, name);
			if (file != null) {
				scopeFiles.add(file);
			}
		}
		// one copy per version so that references inside it resolve within that version's stub world
		VirtualFile system = createStubFile(version, SYSTEM_DIRECTORY, SYSTEM_FILE);
		if (system != null) {
			system.putUserData(SYSTEM_STUB, Boolean.TRUE);
			scopeFiles.add(system);
		}
		VirtualFile events = createStubFile(version, EVENTS_FILE);
		if (events != null) {
			events.putUserData(EVENTS_STUB, Boolean.TRUE);
		}
		return new StubSet(version, scopeFiles, events);
	}

	@Nullable
	private VirtualFile createStubFile(@NotNull String version, @NotNull String name)
	{
		return createStubFile(version, version, name);
	}

	/** A read-only in-memory copy of {@code /stubs/<directory>/<name>} tagged as belonging to {@code version}. */
	@Nullable
	private VirtualFile createStubFile(@NotNull String version, @NotNull String directory, @NotNull String name)
	{
		String text = readResource("/stubs/" + directory + "/" + name);
		if (text == null) {
			return null;
		}
		LightVirtualFile file = new LightVirtualFile(name, KmrPascalFileType.INSTANCE, text);
		file.setWritable(false);
		file.putUserData(STUB_VERSION, version);
		return file;
	}

	@Nullable
	private static String readResource(@NotNull String path)
	{
		try (InputStream stream = KmrPascalStubLibrary.class.getResourceAsStream(path)) {
			return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}

}
