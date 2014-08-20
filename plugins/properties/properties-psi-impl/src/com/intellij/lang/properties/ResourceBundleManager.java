/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.lang.properties;

import com.intellij.lang.properties.CustomResourceBundle;
import com.intellij.lang.properties.PropertiesImplUtil;
import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NullableComputable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/**
 * @author Dmitry Batkovich
 */
@State(
  name = "ResourceBundleManager",
  storages = {@Storage(file = StoragePathMacros.PROJECT_CONFIG_DIR + "/resourceBundles.xml")})
public class ResourceBundleManager implements PersistentStateComponent<ResourceBundleManagerState> {
  private final static Logger LOG = Logger.getInstance(ResourceBundleManager.class);
  private final static Locale DEFAULT_LOCALE = new Locale("", "", "");

  private ResourceBundleManagerState myState = new ResourceBundleManagerState();

  public ResourceBundleManager(final PsiManager manager) {
    manager.addPsiTreeChangeListener(new PsiTreeChangeAdapter() {
      @Override
      public void childMoved(@NotNull PsiTreeChangeEvent event) {
        final PsiElement child = event.getChild();
        if (!(child instanceof PsiFile)) {
          return;
        }
        final PropertiesFile propertiesFile = PropertiesImplUtil.getPropertiesFile((PsiFile)child);
        if (propertiesFile == null) {
          return;
        }
        final String oldParentUrl = getUrl(event.getOldParent());
        final String newParentUrl = getUrl(event.getNewParent());
        if (oldParentUrl == null || newParentUrl == null) {
          return;
        }
        final String newUrl = propertiesFile.getVirtualFile().getUrl();
        final String oldUrl = oldParentUrl + newUrl.substring(newParentUrl.length());
        if (myState.getDissociatedFiles().remove(oldUrl)) {
          myState.getDissociatedFiles().add(newUrl);
        }

        for (CustomResourceBundleState customResourceBundleState : myState.getCustomResourceBundles()) {
          if (customResourceBundleState.getFileUrls().remove(oldUrl)) {
            customResourceBundleState.getFileUrls().add(newUrl);
            break;
          }
        }
      }

      @Nullable
      private String getUrl(PsiElement element) {
        return !(element instanceof PsiDirectory) ? null : ((PsiDirectory)element).getVirtualFile().getUrl();
      }

      @Override
      public void childReplaced(@NotNull PsiTreeChangeEvent event) {
        super.childReplaced(event);
      }

      @Override
      public void beforeChildMovement(@NotNull PsiTreeChangeEvent event) {
        super.beforeChildMovement(event);
      }

      @Override
      public void propertyChanged(@NotNull PsiTreeChangeEvent event) {
        super.propertyChanged(event);
      }

      @Override
      public void childRemoved(@NotNull PsiTreeChangeEvent event) {
        final PsiElement child = event.getChild();
        if (!(child instanceof PsiFile)) {
          return;
        }
        PropertiesFile file = PropertiesImplUtil.getPropertiesFile((PsiFile)child);
        if (file == null) {
          return;
        }
        final VirtualFile virtualFile = file.getVirtualFile();
        final String url = virtualFile.getUrl();
        myState.getDissociatedFiles().remove(url);
        for (CustomResourceBundleState customResourceBundleState : myState.getCustomResourceBundles()) {
          if (customResourceBundleState.getFileUrls().remove(url)) {
            if (customResourceBundleState.getFileUrls().size() < 2) {
              myState.getCustomResourceBundles().remove(customResourceBundleState);
            }
            break;
          }
        }
      }
    });
  }

  public static ResourceBundleManager getInstance(final Project project) {
    return ServiceManager.getService(project, ResourceBundleManager.class);
  }

  @Nullable
  public String getFullName(final @NotNull PropertiesFile propertiesFile) {
    return ApplicationManager.getApplication().runReadAction(new NullableComputable<String>() {
      public String compute() {
        final PsiDirectory directory = propertiesFile.getParent();
        final String packageQualifiedName = PropertiesUtil.getPackageQualifiedName(directory);
        if (packageQualifiedName == null) {
          return null;
        }
        final StringBuilder qName = new StringBuilder(packageQualifiedName);
        if (qName.length() > 0) {
          qName.append(".");
        }
        qName.append(getBaseName(propertiesFile.getContainingFile()));
        return qName.toString();
      }
    });
  }

  @NotNull
  public Locale getLocale(final @NotNull VirtualFile propertiesFile) {
    final String customResourceBundleName = getCustomResourceBundleName(propertiesFile);

    String name = propertiesFile.getName();
    if (customResourceBundleName != null) {
      name = name.substring(customResourceBundleName.length());
    }

    final Matcher matcher = PropertiesUtil.LOCALE_PATTERN.matcher(name);
    if (matcher.find()) {
      final String rawLocale = matcher.group(1);
      final String[] splittedRawLocale = rawLocale.split("_");
      if (splittedRawLocale.length > 1 && splittedRawLocale[1].length() >= 2) {
        final String language = splittedRawLocale[1];
        final String country = splittedRawLocale.length > 2 ? splittedRawLocale[2] : "";
        final String variant = splittedRawLocale.length > 3 ? splittedRawLocale[3] : "";
        return new Locale(language, country, variant);
      }
    }
    return DEFAULT_LOCALE;
  }

  @NotNull
  public String getBaseName(@NotNull final PsiFile file) {
    return getBaseName(file.getVirtualFile());
  }

  @NotNull
  private String getBaseName(@NotNull final VirtualFile file) {
    final CustomResourceBundleState customResourceBundle = getCustomResourceBundleState(file);
    if (customResourceBundle != null) {
      return customResourceBundle.getBaseName();
    }
    if (isDefaultDissociated(file)) {
      return file.getNameWithoutExtension();
    }
    return PropertiesUtil.getDefaultBaseName(file);
  }


  public void dissociateResourceBundle(final @NotNull ResourceBundle resourceBundle) {
    if (resourceBundle instanceof CustomResourceBundle) {
      final CustomResourceBundleState state =
        getCustomResourceBundleState(resourceBundle.getDefaultPropertiesFile().getVirtualFile());
      LOG.assertTrue(state != null);
      myState.getCustomResourceBundles().remove(state);
    } else {
      for (final PropertiesFile propertiesFile : resourceBundle.getPropertiesFiles()) {
        final VirtualFile file = propertiesFile.getContainingFile().getVirtualFile();
        myState.getDissociatedFiles().add(file.getUrl());
      }
    }
  }

  public void combineToResourceBundle(final @NotNull List<PropertiesFile> propertiesFiles, final String baseName) {
    myState.getCustomResourceBundles()
      .add(new CustomResourceBundleState().addAll(ContainerUtil.map(propertiesFiles, new Function<PropertiesFile, String>() {
        @Override
        public String fun(PropertiesFile file) {
          return file.getVirtualFile().getUrl();
        }
      })).setBaseName(baseName));
  }

  @Nullable
  public CustomResourceBundle getCustomResourceBundle(final @NotNull PropertiesFile file) {
    final VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) {
      return null;
    }
    final CustomResourceBundleState state = getCustomResourceBundleState(virtualFile);
    return state == null ? null : CustomResourceBundle.fromState(state, file.getProject());
  }

  public boolean isDefaultDissociated(final @NotNull VirtualFile virtualFile) {
    final String url = virtualFile.getUrl();
    return myState.getDissociatedFiles().contains(url) || getCustomResourceBundleState(virtualFile) != null;
  }

  @Nullable
  private String getCustomResourceBundleName(final @NotNull VirtualFile virtualFile) {
    final CustomResourceBundleState customResourceBundle = getCustomResourceBundleState(virtualFile);
    return customResourceBundle == null ? null : customResourceBundle.getBaseName();
  }

  @Nullable
  private CustomResourceBundleState getCustomResourceBundleState(final @NotNull VirtualFile virtualFile) {
    final String url = virtualFile.getUrl();
    for (CustomResourceBundleState customResourceBundleState : myState.getCustomResourceBundles()) {
      if (customResourceBundleState.getFileUrls().contains(url)) {
        return customResourceBundleState;
      }
    }
    return null;
  }

  @Nullable
  @Override
  public ResourceBundleManagerState getState() {
    return myState.isEmpty() ? null : myState;
  }

  @Override
  public void loadState(ResourceBundleManagerState state) {
    myState = state.removeNonExistentFiles();
  }
}
