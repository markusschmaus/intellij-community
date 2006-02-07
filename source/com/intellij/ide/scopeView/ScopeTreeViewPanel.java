package com.intellij.ide.scopeView;

import com.intellij.ide.CopyPasteManagerEx;
import com.intellij.ide.DeleteProvider;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.IdeView;
import com.intellij.ide.projectView.impl.ModuleGroup;
import com.intellij.ide.util.DeleteHandler;
import com.intellij.ide.util.EditorHelper;
import com.intellij.ide.util.PackageUtil;
import com.intellij.ide.util.scopeChooser.ScopeEditorPanel;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.DataConstantsEx;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.localVcs.LvcsAction;
import com.intellij.openapi.localVcs.impl.LvcsIntegration;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.impl.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.actions.ModuleDeleteProvider;
import com.intellij.openapi.util.DefaultJDOMExternalizer;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMExternalizable;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.packageDependencies.DependencyUISettings;
import com.intellij.packageDependencies.DependencyValidationManager;
import com.intellij.packageDependencies.ui.*;
import com.intellij.psi.*;
import com.intellij.psi.search.scope.packageSet.NamedScope;
import com.intellij.psi.search.scope.packageSet.NamedScopesHolder;
import com.intellij.psi.search.scope.packageSet.PackageSet;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeToolTipHandler;
import com.intellij.util.EditSourceOnDoubleClickHandler;
import com.intellij.util.containers.HashSet;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jdom.Element;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * User: anna
 * Date: 25-Jan-2006
 */
public class ScopeTreeViewPanel extends JPanel implements JDOMExternalizable, DataProvider {
  private static final Logger LOG = Logger.getInstance("com.intellij.ide.scopeView.ScopeTreeViewPanel");
  private IdeView myIdeView = new  MyIdeView();
  private MyPsiTreeChangeAdapter myPsiTreeChangeAdapter = new MyPsiTreeChangeAdapter();
  private ModuleRootListener myModuleRootListener = new MyModuleRootListener();

  private JTree myTree;
  private final Project myProject;
  private TreeModelBuilder myBuilder;
  private JComboBox myScopesCombo;
  private JPanel myPanel;

  private JPanel myToolbar;

  public String CURRENT_SCOPE;

  private boolean myInitialized = false;

  private TreeExpantionMonitor myTreeExpantionMonitor;
  private CopyPasteManagerEx.CopyPasteDelegator myCopyPasteDelegator;
  private final MyDeletePSIElementProvider myDeletePSIElementProvider = new MyDeletePSIElementProvider();
  private final ModuleDeleteProvider myDeleteModuleProvider = new ModuleDeleteProvider();

  public ScopeTreeViewPanel(final Project project) {
    super(new BorderLayout());
    myProject = project;
    initScopes();
    initTree();
    initToolbar();
    add(myPanel, BorderLayout.CENTER);
  }

  private void initToolbar() {
    DefaultActionGroup group = new DefaultActionGroup();
    group.add(new CompactEmptyMiddlePackagesAction());
    myToolbar.setLayout(new BorderLayout());
    myToolbar.add(ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, group, true).getComponent(), BorderLayout.EAST);
  }

  private final class CompactEmptyMiddlePackagesAction extends ToggleAction {
    CompactEmptyMiddlePackagesAction() {
      super(IdeBundle.message("action.compact.empty.middle.packages"),
            IdeBundle.message("action.compact.empty.middle.packages"), ScopeEditorPanel.COMPACT_EMPTY_MIDDLE_PACKAGES_ICON);
    }

    public boolean isSelected(AnActionEvent event) {
      return DependencyUISettings.getInstance().UI_COMPACT_EMPTY_MIDDLE_PACKAGES;
    }

    public void setSelected(AnActionEvent event, boolean flag) {
      DependencyUISettings.getInstance().UI_COMPACT_EMPTY_MIDDLE_PACKAGES = flag;
      final DependencyValidationManager holder = DependencyValidationManager.getInstance(myProject);
      final NamedScope scope = (NamedScope)myScopesCombo.getSelectedItem();
      refreshScope(scope, holder, true);
    }
  }

  public void initListeners(){
    myInitialized = true;
    PsiManager.getInstance(myProject).addPsiTreeChangeListener(myPsiTreeChangeAdapter);
    ProjectRootManager.getInstance(myProject).addModuleRootListener(myModuleRootListener);
  }

  public void dispose(){
    PsiManager.getInstance(myProject).removePsiTreeChangeListener(myPsiTreeChangeAdapter);
    ProjectRootManager.getInstance(myProject).removeModuleRootListener(myModuleRootListener);
  }

  public boolean isInitialized() {
    return myInitialized;
  }

  private void initScopes(){
    final DependencyValidationManager holder = DependencyValidationManager.getInstance(myProject);
    reloadScopes(holder);
    myScopesCombo.setRenderer(new DefaultListCellRenderer(){
      public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        final Component cellRendererComponent = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        if (value instanceof NamedScope){
          setText(((NamedScope)value).getName());
        }
        return cellRendererComponent;
      }
    });
    myScopesCombo.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        final Object selectedItem = myScopesCombo.getSelectedItem();
        if (selectedItem instanceof NamedScope) {
          final NamedScope scope = (NamedScope)selectedItem;
          refreshScope(scope, holder, true);
          if (scope != holder.getProjectScope()) {
            CURRENT_SCOPE = scope.getName();
          }
        }
      }
    });
  }

  public JPanel getPanel() {
    return this;
  }

  private void initTree() {
    myTree.setCellRenderer(new MyTreeCellRenderer());
    myTree.setRootVisible(false);
    myTree.setShowsRootHandles(true);
    UIUtil.setLineStyleAngled(myTree);
    TreeToolTipHandler.install(myTree);
    TreeUtil.installActions(myTree);
    PopupHandler.installPopupHandler(myTree,
                                     (ActionGroup)ActionManager.getInstance().getAction(IdeActions.GROUP_SCOPE_VIEW_POPUP),
                                     ActionPlaces.SCOPE_VIEW_POPUP, ActionManager.getInstance());
    EditSourceOnDoubleClickHandler.install(myTree);
    myCopyPasteDelegator = new CopyPasteManagerEx.CopyPasteDelegator(myProject, this) {
      protected PsiElement[] getSelectedElements() {
        return getSelectedPsiElements();
      }
    };
    myTreeExpantionMonitor = TreeExpantionMonitor.install(myTree, myProject);
    myTree.addTreeWillExpandListener(new ScopeTreeViewExpander(myTree));
  }

  private PsiElement[] getSelectedPsiElements() {
    final TreePath[] treePaths = myTree.getSelectionPaths();
    if (treePaths != null){
      Set<PsiElement> result = new HashSet<PsiElement>();
      for (TreePath path : treePaths) {
        PackageDependenciesNode node = (PackageDependenciesNode)path.getLastPathComponent();
        final PsiElement psiElement = node.getPsiElement();
        if (psiElement != null && psiElement.isValid()){
          result.add(psiElement);
        }
      }
      return result.toArray(new PsiElement[result.size()]);
    }
    return PsiElement.EMPTY_ARRAY;
  }

  private void refreshScope(NamedScope scope, final NamedScopesHolder holder, boolean showProgress) {
    myTreeExpantionMonitor.freeze();
    final DefaultMutableTreeNode root = ((DefaultMutableTreeNode)myTree.getModel().getRoot());
    root.removeAllChildren();
    if (scope == null){ //was deleted
      scope = DependencyValidationManager.getInstance(myProject).getProjectScope();
      reloadScopes(holder);
    }
    final PackageSet packageSet = scope.getValue();
    final DependenciesPanel.DependencyPanelSettings settings = new DependenciesPanel.DependencyPanelSettings();
    settings.UI_FILTER_LEGALS = true;
    settings.UI_GROUP_BY_SCOPE_TYPE = false;
    settings.UI_SHOW_FILES = true;
    settings.UI_GROUP_BY_FILES = true;
    myBuilder = new TreeModelBuilder(myProject, false, new TreeModelBuilder.Marker() {
      public boolean isMarked(PsiFile file) {
        return packageSet.contains(file, holder);
      }
    }, settings);
    final TreeModelBuilder.TreeModel treeModel = myBuilder.build(myProject, showProgress);
    final DefaultMutableTreeNode scopeRootNode = (DefaultMutableTreeNode)treeModel.getRoot();
    for(int i = scopeRootNode.getChildCount() - 1; i >= 0; i--){
      root.add ((MutableTreeNode)scopeRootNode.getChildAt(i));
    }
    ((DefaultTreeModel)myTree.getModel()).reload();
    myTreeExpantionMonitor.restore();
  }

  public void reloadScopes(final NamedScopesHolder holder) {
    myScopesCombo.setModel(new DefaultComboBoxModel());
    final NamedScope[] scopes = holder.getScopes();
    for (NamedScope scope : scopes) {
      ((DefaultComboBoxModel)myScopesCombo.getModel()).addElement(scope);
    }
  }

  public void readExternal(Element element) throws InvalidDataException {
    DefaultJDOMExternalizer.readExternal(this, element);
  }

  public void writeExternal(Element element) throws WriteExternalException {
    DefaultJDOMExternalizer.writeExternal(this, element);
  }

  public void selectCurrentScope() {
    final DependencyValidationManager holder = DependencyValidationManager.getInstance(myProject);
    final NamedScope scope = CURRENT_SCOPE != null ? holder.getScope(CURRENT_SCOPE) : holder.getProjectScope();
    myScopesCombo.setSelectedItem(scope);
    refreshScope(scope, holder, true);
  }

  @Nullable
  public Object getData(String dataId) {
    if (dataId.equals(DataConstants.PROJECT)){
      return myProject;
    }
    if (dataId.equals(DataConstants.MODULE)){
      final TreePath selectionPath = myTree.getSelectionPath();
      if (selectionPath != null){
        PackageDependenciesNode node = (PackageDependenciesNode)selectionPath.getLastPathComponent();
        if (node instanceof ModuleNode){
          return ((ModuleNode)node).getModule();
        } else {
          final PsiElement psiElement = node.getPsiElement();
          if (psiElement != null){
            return ModuleUtil.findModuleForPsiElement(psiElement);
          }
        }
      }
    }
    if (dataId.equals(DataConstants.PSI_ELEMENT)){
      final TreePath selectionPath = myTree.getSelectionPath();
      if (selectionPath != null){
        PackageDependenciesNode node = (PackageDependenciesNode)selectionPath.getLastPathComponent();
        return node != null ? node.getPsiElement() : null;
      }
    }
    final TreePath[] treePaths = myTree.getSelectionPaths();
    if (treePaths != null){
      if (dataId.equals(DataConstantsEx.PSI_ELEMENT_ARRAY)) {
        Set<PsiElement> psiElements = new HashSet<PsiElement>();
        for (TreePath treePath : treePaths) {
          final PackageDependenciesNode node = (PackageDependenciesNode)treePath.getLastPathComponent();
          final PsiElement psiElement = node.getPsiElement();
          if (psiElement != null){
            psiElements.add(psiElement);
          }
        }
        return psiElements.isEmpty() ? null : psiElements.toArray(new PsiElement[psiElements.size()]);
      }
    }
    if (dataId.equals(DataConstants.IDE_VIEW)){
      return myIdeView;
    }
    if (DataConstantsEx.CUT_PROVIDER.equals(dataId)) {
      return myCopyPasteDelegator.getCutProvider();
    }
    if (DataConstantsEx.COPY_PROVIDER.equals(dataId)) {
      return myCopyPasteDelegator.getCopyProvider();
    }
    if (DataConstantsEx.PASTE_PROVIDER.equals(dataId)) {
      return myCopyPasteDelegator.getPasteProvider();
    }
    if (DataConstantsEx.DELETE_ELEMENT_PROVIDER.equals(dataId)) {
      if (getSelectedModules() != null){
        return myDeleteModuleProvider;
      }
      if (getSelectedPsiElements() != null){
        return myDeletePSIElementProvider;
      }
    }
    return null;
  }

  private Module[] getSelectedModules(){
    final TreePath[] treePaths = myTree.getSelectionPaths();
    if (treePaths != null){
      Set<Module> result = new HashSet<Module>();
      for (TreePath path : treePaths) {
        PackageDependenciesNode node = (PackageDependenciesNode)path.getLastPathComponent();
        if (node instanceof ModuleNode){
          result.add(((ModuleNode)node).getModule());
        } else if (node instanceof ModuleGroupNode){
          final ModuleGroupNode groupNode = (ModuleGroupNode)node;
          final ModuleGroup moduleGroup = groupNode.getModuleGroup();
          result.addAll(Arrays.asList(moduleGroup.modulesInGroup(myProject, true)));
        }
      }
      return result.isEmpty() ? null : result.toArray(new Module[result.size()]);
    }
    return null;
  }


  private static class MyTreeCellRenderer extends ColoredTreeCellRenderer {
    public void customizeCellRenderer(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
      if (value instanceof PackageDependenciesNode) {
        PackageDependenciesNode node = (PackageDependenciesNode)value;
        if (expanded) {
          setIcon(node.getOpenIcon());
        }
        else {
          setIcon(node.getClosedIcon());
        }
        final SimpleTextAttributes regularAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES;
        final TextAttributes textAttributes = regularAttributes.toTextAttributes();
        textAttributes.setForegroundColor(node.getStatus().getColor());
        final String text = node.toString();
        if (text != null) {
          append(text, SimpleTextAttributes.fromTextAttributes(textAttributes));
        }
      }
    }
  }

  private class MyPsiTreeChangeAdapter extends PsiTreeChangeAdapter {

    public void beforeChildAddition(final PsiTreeChangeEvent event) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        public void run() {
          final PsiElement element = event.getParent();
          if (element instanceof PsiDirectory || element instanceof PsiPackage) {
            final PsiElement child = event.getChild();
            if (child instanceof PsiFile) {
              final PackageDependenciesNode rootToReload = myBuilder.addFileNode((PsiFile)child);
              final DefaultTreeModel treeModel = (DefaultTreeModel)myTree.getModel();
              if (rootToReload != null) {
                TreeUtil.sort(rootToReload, new DependecyNodeComparator());
                treeModel.reload(rootToReload);
              } else {
                TreeUtil.sort(treeModel, new DependecyNodeComparator());
                treeModel.reload();
              }
            }
          }
        }
      }, ModalityState.NON_MMODAL);
    }

    public void beforeChildRemoval(final PsiTreeChangeEvent event) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        public void run() {
          final PsiElement child = event.getChild();
          final PsiElement parent = event.getParent();
          if (parent instanceof PsiDirectory && (child instanceof PsiFile || child instanceof PsiDirectory)) {
            final PackageDependenciesNode node = myBuilder.removeNode(child, (PsiDirectory)parent);
            if (node != null) {
              ((DefaultTreeModel)myTree.getModel()).reload(node);
            }
          }
        }
      }, ModalityState.NON_MMODAL);
    }

    public void beforeChildMovement(final PsiTreeChangeEvent event) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        public void run() {
          final PsiElement oldParent = event.getOldParent();
          final PsiElement newParent = event.getNewParent();
          PsiElement child = event.getChild();
          if (oldParent instanceof PsiDirectory && newParent instanceof PsiDirectory) {
            if (child instanceof PsiFile) {
              myBuilder.removeNode(child, (PsiDirectory)oldParent);
              myBuilder.addFileNode((PsiFile)child);
            }
          }
        }
      }, ModalityState.NON_MMODAL);
    }

    public void beforeChildrenChange(final PsiTreeChangeEvent event) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        public void run() {
          final PsiElement parent = event.getParent();
          if (parent instanceof PsiJavaFile) {
            final PackageDependenciesNode parentNode = myBuilder.getFileParentNode((PsiFile)parent);
            final TreePath treePath = new TreePath(parentNode.getPath());
            if (!myTree.isCollapsed(treePath)) {
              myTree.collapsePath(treePath);
              myTree.expandPath(treePath);
              TreeUtil.sort(parentNode, new DependecyNodeComparator());
            }
          }
        }
      }, ModalityState.NON_MMODAL);
    }
  }

  private class MyModuleRootListener implements ModuleRootListener {
    public void beforeRootsChange(ModuleRootEvent event) {
    }

    public void rootsChanged(ModuleRootEvent event) {
      final DependencyValidationManager holder = DependencyValidationManager.getInstance(myProject);
      refreshScope(CURRENT_SCOPE != null ? holder.getScope(CURRENT_SCOPE) : holder.getProjectScope(), holder, false);
    }
  }

  private class MyIdeView implements IdeView {
    public void selectElement(final PsiElement element) {
      if (element != null) {
        final boolean isDirectory = element instanceof PsiDirectory;
        if (!isDirectory) {
          Editor editor = EditorHelper.openInEditor(element);
          if (editor != null) {
            ToolWindowManager.getInstance(myProject).activateEditorComponent();
          }
        }
      }
    }

    private PsiDirectory getDirectory() {
      final TreePath[] selectedPaths = myTree.getSelectionPaths();
      if (selectedPaths != null) {
        if (selectedPaths.length != 1) return null;
        TreePath path = selectedPaths[0];
        final PackageDependenciesNode node = (PackageDependenciesNode)path.getLastPathComponent();
        if (node instanceof DirectoryNode) {
          return (PsiDirectory)node.getPsiElement();
        }
        else if (node instanceof ClassNode) {
          final PsiElement psiClass = node.getPsiElement();
          LOG.assertTrue(psiClass != null);
          final PsiFile psiFile = psiClass.getContainingFile();
          LOG.assertTrue(psiFile != null);
          return psiFile.getContainingDirectory();
        }
        else if (node instanceof FileNode) {
          final PsiFile psiFile = (PsiFile)node.getPsiElement();
          LOG.assertTrue(psiFile != null);
          return psiFile.getContainingDirectory();
        }
      }
      return null;
    }

    public PsiDirectory[] getDirectories() {
      PsiDirectory directory = getDirectory();
      return directory == null ? PsiDirectory.EMPTY_ARRAY : new PsiDirectory[]{directory};
    }

    @Nullable
    public PsiDirectory getOrChooseDirectory() {
      return PackageUtil.getOrChooseDirectory(this);
    }
  }

  private final class MyDeletePSIElementProvider implements DeleteProvider {
    public boolean canDeleteElement(DataContext dataContext) {
      final PsiElement[] elements = getSelectedPsiElements();
      return DeleteHandler.shouldEnableDeleteAction(elements);
    }

    public void deleteElement(DataContext dataContext) {
      List<PsiElement> allElements = Arrays.asList(getSelectedPsiElements());
      ArrayList<PsiElement> validElements = new ArrayList<PsiElement>();
      for (PsiElement psiElement : allElements) {
        if (psiElement != null && psiElement.isValid()) validElements.add(psiElement);
      }
      final PsiElement[] elements = validElements.toArray(new PsiElement[validElements.size()]);

      LvcsAction action = LvcsIntegration.checkinFilesBeforeRefactoring(myProject, IdeBundle.message("progress.deleting"));
      try {
        DeleteHandler.deletePsiElement(elements, myProject);
      }
      finally {
        LvcsIntegration.checkinFilesAfterRefactoring(myProject, action);
      }
    }
  }
}
