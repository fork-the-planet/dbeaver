/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.tools.compare.simple.ui;

import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IExportWizard;
import org.eclipse.ui.IWorkbench;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.tools.compare.simple.CompareObjectsExecutor;
import org.jkiss.dbeaver.tools.compare.simple.CompareObjectsSettings;
import org.jkiss.dbeaver.tools.compare.simple.CompareReport;
import org.jkiss.dbeaver.tools.compare.simple.CompareReportRenderer;
import org.jkiss.dbeaver.tools.compare.simple.ui.internal.CompareUIMessages;
import org.jkiss.dbeaver.ui.DialogSettingsDelegate;
import org.jkiss.dbeaver.ui.ShellUtils;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.DialogUtils;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class CompareObjectsWizard extends Wizard implements IExportWizard {

    private static final Log log = Log.getLog(CompareObjectsWizard.class);

    private static final String RS_COMPARE_WIZARD_DIALOG_SETTINGS = "CompareWizard";//$NON-NLS-1$

    private final CompareObjectsSettings settings;

    public CompareObjectsWizard(List<DBNDatabaseNode> nodes) {
        this.settings = new CompareObjectsSettings(nodes);
        this.settings.setOutputFolder(DialogUtils.getCurDialogFolder());

        IDialogSettings section = UIUtils.getDialogSettings(RS_COMPARE_WIZARD_DIALOG_SETTINGS);
        setDialogSettings(section);

        settings.loadFrom(new DialogSettingsDelegate(section));
    }

    @Override
    public void dispose() {
        super.dispose();
    }

    public CompareObjectsSettings getSettings() {
        return settings;
    }

    @Override
    public void addPages() {
        super.addPages();
        addPage(new CompareObjectsPageSettings());
        addPage(new CompareObjectsPageOutput());
    }

    @Override
    public void init(IWorkbench workbench, IStructuredSelection currentSelection) {
        setWindowTitle(CompareUIMessages.compare_objects_wizard_title);
        setNeedsProgressMonitor(true);
    }

    private void showError(String error) {
        if (CommonUtils.isNotEmpty(error)) {
            DBWorkbench.getPlatformUI().showError(CompareUIMessages.compare_objects_wizard_error_title, error);
        }
    }

    @Override
    public boolean performFinish() {
        // Save settings
        getSettings().saveTo(new DialogSettingsDelegate(getDialogSettings()));
        showError(null);

        // Compare
        final CompareObjectsExecutor executor = new CompareObjectsExecutor(settings);
        try {
            UIUtils.run(getContainer(), true, true, monitor -> {
                try {
                    CompareReport report = generateReport(monitor, executor);
                    renderReport(monitor, report);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
            UIUtils.showMessageBox(
                getShell(),
                CompareUIMessages.compare_objects_wizard_finish_report_title,
                CompareUIMessages.compare_objects_wizard_finish_report_info,
                SWT.ICON_INFORMATION
            );
        } catch (InvocationTargetException e) {
            if (executor.getInitializeError() != null) {
                showError(executor.getInitializeError().getMessage());
            } else {
                log.error(e.getTargetException());
                showError(e.getTargetException().getMessage());
            }
            return false;
        } catch (InterruptedException e) {
            showError("Compare interrupted");
            return false;
        } finally {
            executor.dispose();
        }

        // Done
        return true;
    }

    private CompareReport generateReport(DBRProgressMonitor monitor, CompareObjectsExecutor executor)
    throws DBException, InterruptedException {
        monitor.beginTask("Compare objects", 1000);
        CompareReport report = executor.compareObjects(monitor, getSettings().getNodes());
        monitor.done();
        return report;
    }

    private void renderReport(DBRProgressMonitor monitor, CompareReport report) {
        try {
            Path reportFile;
            if (Objects.requireNonNull(settings.getOutputType()) == CompareObjectsSettings.OutputType.BROWSER) {
                reportFile = Files.createTempFile(
                    DBWorkbench.getPlatform().getTempFolder(monitor, "compare-report"),
                    "compare",
                    ".html"
                );
            } else {
                StringBuilder fileName = new StringBuilder("compare"); //"compare-report.html";
                if (report.getNodes().size() <= 3) {
                    for (DBNDatabaseNode node : report.getNodes()) {
                        fileName.append("-").append(CommonUtils.escapeIdentifier(node.getName()));
                    }
                    fileName.append("-report.html");
                } else {
                    fileName.append("-report").append("-").append(RuntimeUtils.getCurrentTimeStamp()).append(".html");
                }
                Path parentFolder = Path.of(settings.getOutputFolder());
                if (!Files.exists(parentFolder)) {
                    Files.createDirectories(parentFolder);
                }
                reportFile = parentFolder.resolve(fileName.toString());
            }

            try (OutputStream outputStream = Files.newOutputStream(reportFile)) {
                monitor.beginTask("Render report", report.getReportLines().size());
                CompareReportRenderer reportRenderer = new CompareReportRenderer();
                reportRenderer.renderReport(monitor, report, getSettings(), outputStream);
                monitor.done();
            }
            if (settings.getOutputType() == CompareObjectsSettings.OutputType.BROWSER) {
                ShellUtils.launchProgram(reportFile.toAbsolutePath().toString());
            }
        } catch (IOException e) {
            showError(e.getMessage());
            log.error(e);
        }
    }

}