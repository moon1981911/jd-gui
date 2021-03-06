/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.gui.controller;

import org.jd.gui.api.API;
import org.jd.gui.api.feature.*;
import org.jd.gui.api.model.Container;
import org.jd.gui.api.model.Indexes;
import org.jd.gui.model.configuration.Configuration;
import org.jd.gui.model.history.History;
import org.jd.gui.service.actions.ContextualActionsFactoryService;
import org.jd.gui.service.container.ContainerFactoryService;
import org.jd.gui.service.fileloader.FileLoaderService;
import org.jd.gui.service.indexer.IndexerService;
import org.jd.gui.service.mainpanel.PanelFactoryService;
import org.jd.gui.service.pastehandler.PasteHandlerService;
import org.jd.gui.service.platform.PlatformService;
import org.jd.gui.service.preferencespanel.PreferencesPanelService;
import org.jd.gui.service.sourcesaver.SourceSaverService;
import org.jd.gui.service.treenode.TreeNodeFactoryService;
import org.jd.gui.service.type.TypeFactoryService;
import org.jd.gui.service.uriloader.UriLoaderService;
import org.jd.gui.spi.*;
import org.jd.gui.util.exception.ExceptionUtil;
import org.jd.gui.util.net.UriUtil;
import org.jd.gui.util.swing.SwingUtil;
import org.jd.gui.view.MainView;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class MainController implements API {
    protected Configuration configuration;
    protected MainView mainView;

    protected GoToController goToController;
    protected OpenTypeController openTypeController;
    protected OpenTypeHierarchyController openTypeHierarchyController;
    protected PreferencesController preferencesController;
    protected SearchInConstantPoolsController searchInConstantPoolsController;
    protected SaveAllSourcesController saveAllSourcesController;
    protected SelectLocationController selectLocationController;
    protected AboutController aboutController;

    protected History history = new History();
    protected JComponent currentPage = null;
    protected ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    protected ArrayList<IndexesChangeListener> containerChangeListeners = new ArrayList<>();

    long debug;

    @SuppressWarnings("unchecked")
    public MainController(Configuration configuration) {
        debug = System.currentTimeMillis();
        this.configuration = configuration;

        SwingUtil.invokeLater(() -> {
            if (PlatformService.getInstance().isLinux()) {
                // Fix for GTKLookAndFeel
                SwingUtil.installGtkPopupBugWorkaround();
            }

            // Create main frame
            mainView = new MainView(
                configuration, this, history,
                e -> onOpen(),
                e -> onClose(),
                e -> onSaveSource(),
                e -> onSaveAllSources(),
                e -> System.exit(0),
                e -> onCopy(),
                e -> onPaste(),
                e -> onSelectAll(),
                e -> onFind(),
                e -> onFindPrevious(),
                e -> onFindNext(),
                e -> onFindCriteriaChanged(),
                () -> onFindCriteriaChanged(),
                e -> onOpenType(),
                e -> onOpenTypeHierarchy(),
                e -> onGoTo(),
                e -> openURI(history.backward()),
                e -> openURI(history.forward()),
                e -> onSearch(),
                e -> onJdWebSite(),
                e -> onWikipedia(),
                e -> onPreferences(),
                e -> onAbout(),
                () -> panelClosed(),
                page -> onCurrentPageChanged((JComponent)page),
                file -> openFile((File)file));
        });
	}
	
	// --- Show GUI --- //
    @SuppressWarnings("unchecked")
	public void show(List<File> files) {
        SwingUtil.invokeLater(() -> {
            // Show main frame
            mainView.show(configuration.getMainWindowLocation(), configuration.getMainWindowSize(), configuration.isMainWindowMaximize());
            if (!files.isEmpty()) {
                openFiles(files);
            }
        });

        // Background initializations
        executor.schedule(() -> {
            // Background service initialization
            ContextualActionsFactoryService.getInstance();
            ContainerFactoryService.getInstance();
            FileLoaderService.getInstance();
            IndexerService.getInstance();
            PasteHandlerService.getInstance();
            PreferencesPanelService.getInstance();
            TreeNodeFactoryService.getInstance();
            TypeFactoryService.getInstance();
            UriLoaderService.getInstance();

            SwingUtil.invokeLater(() -> {
                // Populate recent files menu
                mainView.updateRecentFilesMenu(configuration.getRecentFiles());
                // Background controller creation
                JFrame mainFrame = mainView.getMainFrame();
                saveAllSourcesController = new SaveAllSourcesController(MainController.this, mainFrame);
                containerChangeListeners.add(openTypeController = new OpenTypeController(MainController.this, executor, mainFrame));
                containerChangeListeners.add(openTypeHierarchyController = new OpenTypeHierarchyController(MainController.this, executor, mainFrame));
                goToController = new GoToController(configuration, mainFrame);
                containerChangeListeners.add(searchInConstantPoolsController = new SearchInConstantPoolsController(MainController.this, executor, mainFrame));
                preferencesController = new PreferencesController(configuration, mainFrame, PreferencesPanelService.getInstance().getProviders());
                selectLocationController = new SelectLocationController(MainController.this, mainFrame);
                aboutController = new AboutController(mainFrame);
                // Add listeners
                mainFrame.addComponentListener(new MainFrameListener(configuration));
                // Set drop files transfer handler
                mainFrame.setTransferHandler(new FilesTransferHandler());
                // Background class loading
                new JFileChooser().addChoosableFileFilter(new FileNameExtensionFilter("", "dummy"));
                FileSystemView.getFileSystemView().isFileSystemRoot(new File("dummy"));
                new JLayer();
            });
        }, 400, TimeUnit.MILLISECONDS);
    }

	// --- Actions --- //
    protected void onOpen() {
        Map<String, FileLoader> loaders = FileLoaderService.getInstance().getMapProviders();
        StringBuilder sb = new StringBuilder();
        ArrayList<String> extensions = new ArrayList<>(loaders.keySet());

        extensions.sort(null);

        for (String extension : extensions) {
            sb.append("*.").append(extension).append(", ");
        }

        sb.setLength(sb.length()-2);

        String description = sb.toString();
        String[] array = extensions.toArray(new String[0]);
        JFileChooser chooser = new JFileChooser();

        chooser.removeChoosableFileFilter(chooser.getFileFilter());
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("All files (" + description + ")", array));

        for (String extension : extensions) {
            FileLoader loader = loaders.get(extension);
            chooser.addChoosableFileFilter(new FileNameExtensionFilter(loader.getDescription(), loader.getExtensions()));
        }

        chooser.setCurrentDirectory(configuration.getRecentLoadDirectory());

        if (chooser.showOpenDialog(mainView.getMainFrame()) == JFileChooser.APPROVE_OPTION) {
            configuration.setRecentLoadDirectory(chooser.getCurrentDirectory());
            openFile(chooser.getSelectedFile());
        }
	}

    protected void onClose() {
        mainView.closeCurrentTab();
    }

    protected void onSaveSource() {
        if (currentPage instanceof ContentSavable) {
            JFileChooser chooser = new JFileChooser();
            JFrame mainFrame = mainView.getMainFrame();

            chooser.setSelectedFile(new File(configuration.getRecentSaveDirectory(), ((ContentSavable)currentPage).getFileName()));

            if (chooser.showSaveDialog(mainFrame) == JFileChooser.APPROVE_OPTION) {
                File selectedFile = chooser.getSelectedFile();

                configuration.setRecentSaveDirectory(chooser.getCurrentDirectory());

                if (selectedFile.exists()) {
                    String title = "Are you sure?";
                    String message = "The file '" + selectedFile.getAbsolutePath() + "' already isContainsIn.\n Do you want to replace the existing file?";

                    if (JOptionPane.showConfirmDialog(mainFrame, message, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        save(selectedFile);
                    }
                } else {
                    save(selectedFile);
                }
            }
        }
    }

    protected void save(File selectedFile) {
        try (OutputStream os = new FileOutputStream(selectedFile)) {
            ((ContentSavable)currentPage).save(this, os);
        } catch (IOException e) {
            assert ExceptionUtil.printStackTrace(e);
        }
    }

    protected void onSaveAllSources() {
        if (! saveAllSourcesController.isActivated()) {
            JComponent currentPanel = mainView.getSelectedMainPanel();

            if (currentPanel instanceof SourcesSavable) {
                SourcesSavable sourcesSavable = (SourcesSavable)currentPanel;
                JFileChooser chooser = new JFileChooser();
                JFrame mainFrame = mainView.getMainFrame();

                chooser.setSelectedFile(new File(configuration.getRecentSaveDirectory(), sourcesSavable.getSourceFileName()));

                if (chooser.showSaveDialog(mainFrame) == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = chooser.getSelectedFile();

                    configuration.setRecentSaveDirectory(chooser.getCurrentDirectory());

                    if (selectedFile.exists()) {
                        String title = "Are you sure?";
                        String message = "The file '" + selectedFile.getAbsolutePath() + "' already isContainsIn.\n Do you want to replace the existing file?";

                        if (JOptionPane.showConfirmDialog(mainFrame, message, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                            saveAllSourcesController.show(executor, sourcesSavable, selectedFile);
                        }
                    } else {
                        saveAllSourcesController.show(executor, sourcesSavable, selectedFile);
                    }
                }
            }
        }
    }

    protected void onCopy() {
        if (currentPage instanceof ContentCopyable) {
            ((ContentCopyable)currentPage).copy();
        }
    }

    protected void onPaste() {
        try {
            Transferable transferable = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);

            if ((transferable != null) && transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                Object obj = transferable.getTransferData(DataFlavor.stringFlavor);
                PasteHandler pasteHandler = PasteHandlerService.getInstance().get(obj);

                if (pasteHandler != null) {
                    pasteHandler.paste(this, obj);
                }
            }
        } catch (Exception e) {
            assert ExceptionUtil.printStackTrace(e);
        }
    }

    protected void onSelectAll() {
        if (currentPage instanceof ContentSelectable) {
            ((ContentSelectable)currentPage).selectAll();
        }
    }

    protected void onFind() {
        if (currentPage instanceof ContentSearchable) {
            mainView.showFindPanel();
        }
    }

    protected void onFindCriteriaChanged() {
        if (currentPage instanceof ContentSearchable) {
            mainView.setFindBackgroundColor(((ContentSearchable)currentPage).highlightText(mainView.getFindText(), mainView.getFindCaseSensitive()));
        }
    }

    protected void onFindNext() {
        if (currentPage instanceof ContentSearchable) {
            ((ContentSearchable)currentPage).findNext(mainView.getFindText(), mainView.getFindCaseSensitive());
        }
    }

    protected void onOpenType() {
        openTypeController.show(getCollectionOfIndexes(), uri -> openURI(uri));
    }

    protected void onOpenTypeHierarchy() {
        if (currentPage instanceof FocusedTypeGettable) {
            FocusedTypeGettable ftg = (FocusedTypeGettable)currentPage;
            openTypeHierarchyController.show(getCollectionOfIndexes(), ftg.getEntry(), ftg.getFocusedTypeName(), uri -> openURI(uri));
        }
    }

    protected void onGoTo() {
        if (currentPage instanceof LineNumberNavigable) {
            LineNumberNavigable lnn = (LineNumberNavigable)currentPage;
            goToController.show(lnn, lineNumber -> lnn.goToLineNumber(lineNumber));
        }
    }

    protected void onSearch() {
        searchInConstantPoolsController.show(getCollectionOfIndexes(), uri -> openURI(uri));
    }

    protected void onFindPrevious() {
        if (currentPage instanceof ContentSearchable) {
            ContentSearchable cs = (ContentSearchable)currentPage;
            cs.findPrevious(mainView.getFindText(), mainView.getFindCaseSensitive());
        }
    }

    protected void onJdWebSite() {
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                try {
                    desktop.browse(URI.create("http://java-decompiler.github.io"));
                } catch (IOException e) {
                    assert ExceptionUtil.printStackTrace(e);
                }
            }
        }
    }

    protected void onWikipedia() {
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                try {
                    desktop.browse(URI.create("http://en.wikipedia.org/wiki/Java_Decompiler"));
                } catch (IOException e) {
                    assert ExceptionUtil.printStackTrace(e);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void onPreferences() {
        preferencesController.show(() -> {
            checkPreferencesChange(currentPage);
            mainView.preferencesChanged(getPreferences());
        });
    }

    protected void onAbout() {
        aboutController.show();
    }

    protected void onCurrentPageChanged(JComponent page) {
        currentPage = page;
        checkIndexesChange(page);
        checkPreferencesChange(page);
    }

    protected void checkIndexesChange(JComponent page) {
        if (page instanceof IndexesChangeListener) {
            Collection<Indexes> collectionOfIndexes = getCollectionOfIndexes();
            Integer currentHashcode = Integer.valueOf(collectionOfIndexes.hashCode());
            Integer lastHashcode = (Integer)page.getClientProperty("collectionOfIndexes-stamp");

            if (!currentHashcode.equals(lastHashcode)) {
                ((IndexesChangeListener)page).indexesChanged(collectionOfIndexes);
                page.putClientProperty("collectionOfIndexes-stamp", currentHashcode);
            }
        }
    }

    protected void checkPreferencesChange(JComponent page) {
        if (page instanceof PreferencesChangeListener) {
            Map<String, String> preferences = configuration.getPreferences();
            Integer currentHashcode = Integer.valueOf(preferences.hashCode());
            Integer lastHashcode = (Integer)page.getClientProperty("preferences-stamp");

            if (!currentHashcode.equals(lastHashcode)) {
                ((PreferencesChangeListener)page).preferencesChanged(preferences);
                page.putClientProperty("preferences-stamp", currentHashcode);
            }
        }
    }

    // --- Operations --- //
    public void openFile(File file) {
        openFiles(Collections.singletonList(file));
    }

    @SuppressWarnings("unchecked")
    public void openFiles(List<File> files) {
        ArrayList<String> errors = new ArrayList<>();

        for (File file : files) {
            // Check input file
            if (file.exists()) {
                FileLoader loader = getFileLoader(file);
                if ((loader != null) && !loader.accept(this, file)) {
                    errors.add("Invalid input fileloader: '" + file.getAbsolutePath() + "'");
                }
            } else {
                errors.add("File not found: '" + file.getAbsolutePath() + "'");
            }
        }

        if (errors.isEmpty()) {
            for (File file : files) {
                if (openURI(file.toURI())) {
                    configuration.addRecentFile(file);
                    mainView.updateRecentFilesMenu(configuration.getRecentFiles());
                }
            }
        } else {
            StringBuilder messages = new StringBuilder();
            int index = 0;

            for (String error : errors) {
                if (index > 0) {
                    messages.append('\n');
                }
                if (index >= 20) {
                    messages.append("...");
                    break;
                }
                messages.append(error);
                index++;
            }

            JOptionPane.showMessageDialog(mainView.getMainFrame(), messages.toString(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // --- Drop files transfer handler --- //
    protected class FilesTransferHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferHandler.TransferSupport info) {
            return info.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferHandler.TransferSupport info) {
            if (info.isDrop() && info.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                try {
                    openFiles((List<File>)info.getTransferable().getTransferData(DataFlavor.javaFileListFlavor));
                    return true;
                } catch (Exception e) {
                    assert ExceptionUtil.printStackTrace(e);
                }
            }
            return false;
        }
    }

    // --- ComponentListener --- //
    protected class MainFrameListener extends ComponentAdapter {
        protected Configuration configuration;

        public MainFrameListener(Configuration configuration) {
            this.configuration = configuration;
        }

        @Override
        public void componentMoved(ComponentEvent e) {
            JFrame mainFrame = mainView.getMainFrame();

            if ((mainFrame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                configuration.setMainWindowMaximize(true);
            } else {
                configuration.setMainWindowLocation(mainFrame.getLocation());
                configuration.setMainWindowMaximize(false);
            }
        }

        @Override
        public void componentResized(ComponentEvent e) {
            JFrame mainFrame = mainView.getMainFrame();

            if ((mainFrame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                configuration.setMainWindowMaximize(true);
            } else {
                configuration.setMainWindowSize(mainFrame.getSize());
                configuration.setMainWindowMaximize(false);
            }
        }
    }

    protected void panelClosed() {
        SwingUtil.invokeLater(() -> {
            // Fire 'indexesChanged' event
            Collection<Indexes> collectionOfIndexes = getCollectionOfIndexes();
            for (IndexesChangeListener listener : containerChangeListeners) {
                listener.indexesChanged(collectionOfIndexes);
            }
            if (currentPage instanceof IndexesChangeListener) {
                ((IndexesChangeListener)currentPage).indexesChanged(collectionOfIndexes);
            }
        });
    }

    // --- API --- //
    @Override
    @SuppressWarnings("unchecked")
    public boolean openURI(URI uri) {
        if (uri != null) {
            boolean success = mainView.openUri(uri);
            UriLoader uriLoader = getUriLoader(uri);

            if (uriLoader != null) {
                success |= uriLoader.load(this, uri);
            }

            if (success) {
                addURI(uri);
            }
            return success;
        }
        return false;
    }

    @Override
    public boolean openURI(int x, int y, Collection<Container.Entry> entries, String query, String fragment) {
        if (entries != null) {
            if (entries.size() == 1) {
                // Open the single entry uri
                Container.Entry entry = entries.iterator().next();
                return openURI(UriUtil.createURI(this, getCollectionOfIndexes(), entry, query, fragment));
            } else {
                // Multiple entries -> Open a "Select location" popup
                Collection<Indexes> collectionOfIndexes = getCollectionOfIndexes();
                selectLocationController.show(
                    new Point(x+(16+2), y+2),
                    entries,
                    entry -> openURI(UriUtil.createURI(this, collectionOfIndexes, entry, query, fragment)), // entry selected closure
                    () -> {});                                                                              // popup close closure
                return true;
            }
        }

        return false;
    }

    @Override
    public void addURI(URI uri) {
        history.add(uri);
        SwingUtil.invokeLater(() -> {
            mainView.updateHistoryActions();
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends JComponent & UriGettable> void addPanel(String title, Icon icon, String tip, T component) {
        mainView.addMainPanel(title, icon, tip, component);

        if (component instanceof ContentIndexable) {
            Future<Indexes> futureIndexes = executor.submit(() -> ((ContentIndexable)component).index(this));
            Indexes indexes = new Indexes() {
                @Override public void waitIndexers() {
                    try {
                        futureIndexes.get();
                    } catch (Exception e) {
                        assert ExceptionUtil.printStackTrace(e);
                    }
                }
                @Override public Map<String, Collection> getIndex(String name) {
                    try {
                        return futureIndexes.get().getIndex(name);
                    } catch (Exception e) {
                        assert ExceptionUtil.printStackTrace(e);
                        return null;
                    }
                }
            };

            component.putClientProperty("indexes", indexes);

            SwingUtil.invokeLater(() -> {
                // Fire 'indexesChanged' event
                Collection<Indexes> collectionOfIndexes = getCollectionOfIndexes();
                for (IndexesChangeListener listener : containerChangeListeners) {
                    listener.indexesChanged(collectionOfIndexes);
                }
                if (currentPage instanceof IndexesChangeListener) {
                    ((IndexesChangeListener)currentPage).indexesChanged(collectionOfIndexes);
                }
            });
        }

        checkIndexesChange(currentPage);
    }

    @Override public Collection<Action> getContextualActions(Container.Entry entry, String fragment) { return ContextualActionsFactoryService.getInstance().get(this, entry, fragment); }

    @Override public FileLoader getFileLoader(File file) { return FileLoaderService.getInstance().get(this, file); }

    @Override public UriLoader getUriLoader(URI uri) { return UriLoaderService.getInstance().get(this, uri); }

    @Override public PanelFactory getMainPanelFactory(Container container) { return PanelFactoryService.getInstance().get(container); }

    @Override public ContainerFactory getContainerFactory(Path rootPath) { return ContainerFactoryService.getInstance().get(this, rootPath); }

    @Override public TreeNodeFactory getTreeNodeFactory(Container.Entry entry) { return TreeNodeFactoryService.getInstance().get(entry); }

    @Override public TypeFactory getTypeFactory(Container.Entry entry) { return TypeFactoryService.getInstance().get(entry); }

    @Override public Indexer getIndexer(Container.Entry entry) { return IndexerService.getInstance().get(entry); }

    @Override public SourceSaver getSourceSaver(Container.Entry entry) { return SourceSaverService.getInstance().get(entry); }

    @Override public Map<String, String> getPreferences() { return configuration.getPreferences(); }

    @SuppressWarnings("unchecked")
    public Collection<Indexes> getCollectionOfIndexes() {
        List<JComponent> mainPanels = mainView.getMainPanels();
        ArrayList<Indexes> list = new ArrayList<>(mainPanels.size());

        for (JComponent panel : mainPanels) {
            Indexes indexes = (Indexes)panel.getClientProperty("indexes");

            if (indexes != null) {
                list.add(indexes);
            }
        }

        return list;
    }
}
