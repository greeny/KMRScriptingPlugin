package dev.greeny.kmr.language.editor;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import dev.greeny.kmr.language.KmrPascalFileType;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import dev.greeny.kmr.language.unit.KmrPascalEntryPoints;
import dev.greeny.kmr.language.unit.KmrPascalProjectSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Settings | Languages &amp; Frameworks | KMR PascalScript: default game version and per-entry-point overrides. */
public class KmrPascalSettingsConfigurable implements Configurable
{

	private static final String DEFAULT_ROW_VALUE = "(default)";

	private final Project project;
	private JComboBox<String> defaultVersion;
	private DefaultTableModel tableModel;
	private List<VirtualFile> entryPoints = new ArrayList<>();

	public KmrPascalSettingsConfigurable(@NotNull Project project)
	{
		this.project = project;
	}

	@Override
	public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName()
	{
		return "KMR PascalScript";
	}

	@Override
	public @Nullable JComponent createComponent()
	{
		defaultVersion = new JComboBox<>(KmrPascalStubLibrary.VERSIONS.toArray(new String[0]));

		entryPoints = findEntryPoints();
		tableModel = new DefaultTableModel(new Object[]{"Entry point (map script)", "Game version"}, 0)
		{
			@Override
			public boolean isCellEditable(int row, int column)
			{
				return column == 1;
			}
		};
		JBTable table = new JBTable(tableModel);
		JComboBox<String> versionEditor = new JComboBox<>();
		versionEditor.addItem(DEFAULT_ROW_VALUE);
		KmrPascalStubLibrary.VERSIONS.forEach(versionEditor::addItem);
		table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(versionEditor));
		table.getColumnModel().getColumn(1).setPreferredWidth(120);
		table.getColumnModel().getColumn(0).setPreferredWidth(400);

		reset();

		return FormBuilder.createFormBuilder()
			.addLabeledComponent("Default game version (API stubs):", defaultVersion)
			.addComponent(new JBLabel("Entry points are scripts next to a .dat/.map file with the same name. Override the version per map:"))
			.addComponentFillVertically(new JBScrollPane(table), 0)
			.getPanel();
	}

	@Override
	public boolean isModified()
	{
		KmrPascalProjectSettings settings = KmrPascalProjectSettings.getInstance(project);
		if (!settings.getDefaultStubVersion().equals(defaultVersion.getSelectedItem())) {
			return true;
		}
		for (int row = 0; row < entryPoints.size(); row++) {
			String current = settings.getStubVersionOverride(entryPoints.get(row));
			if (!rowValue(row).equals(current == null ? DEFAULT_ROW_VALUE : current)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void apply()
	{
		KmrPascalProjectSettings settings = KmrPascalProjectSettings.getInstance(project);
		Object selected = defaultVersion.getSelectedItem();
		settings.setDefaultStubVersion(selected == null ? KmrPascalStubLibrary.LATEST : selected.toString());
		for (int row = 0; row < entryPoints.size(); row++) {
			String value = rowValue(row);
			settings.setStubVersionOverride(entryPoints.get(row), DEFAULT_ROW_VALUE.equals(value) ? null : value);
		}
		com.intellij.psi.PsiManager.getInstance(project).dropPsiCaches();
		com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart();
	}

	@Override
	public void reset()
	{
		KmrPascalProjectSettings settings = KmrPascalProjectSettings.getInstance(project);
		defaultVersion.setSelectedItem(settings.getDefaultStubVersion());
		tableModel.setRowCount(0);
		for (VirtualFile entryPoint : entryPoints) {
			String override = settings.getStubVersionOverride(entryPoint);
			tableModel.addRow(new Object[]{relativePath(entryPoint), override == null ? DEFAULT_ROW_VALUE : override});
		}
	}

	private String rowValue(int row)
	{
		Object value = tableModel.getValueAt(row, 1);
		return value == null ? DEFAULT_ROW_VALUE : value.toString();
	}

	@NotNull
	private List<VirtualFile> findEntryPoints()
	{
		List<VirtualFile> result = new ArrayList<>();
		if (com.intellij.openapi.project.DumbService.isDumb(project)) {
			return result; // indexing: the table stays empty until the page is reopened
		}
		try {
			for (VirtualFile file : FileTypeIndex.getFiles(KmrPascalFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
				if (KmrPascalEntryPoints.isEntryPoint(project, file)) {
					result.add(file);
				}
			}
		} catch (com.intellij.openapi.project.IndexNotReadyException ignored) {
			result.clear();
		}
		result.sort(Comparator.comparing(VirtualFile::getPath));
		return result;
	}

	@NotNull
	private String relativePath(@NotNull VirtualFile file)
	{
		VirtualFile base = project.getBaseDir();
		String relative = base == null ? null : VfsUtilCore.getRelativePath(file, base);
		return relative == null ? file.getPath() : relative;
	}

}
