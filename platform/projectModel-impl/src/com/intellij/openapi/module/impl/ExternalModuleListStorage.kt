// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.module.impl

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ExternalProjectSystemRegistry
import com.intellij.openapi.roots.ProjectModelElement
import com.intellij.openapi.roots.ProjectModelExternalSource
import org.jdom.Element

@State(name = "ExternalModuleListStorage", storages = arrayOf(Storage("externalModules.xml")))
internal class ExternalModuleListStorage(private val project: Project) : PersistentStateComponent<Element>, ProjectModelElement {
  var loadedState: Set<ModulePath>? = null
    private set

  override fun getState(): Element {
    val e = Element("state")
    val moduleManager = ModuleManagerImpl.getInstanceImpl(project)
    moduleManager.writeExternal(e, getFilteredModuleList(moduleManager.modules, true))
    return e
  }

  override fun loadState(state: Element) {
    loadedState = ModuleManagerImpl.getPathsToModuleFiles(state)
  }

  override fun getExternalSource(): ProjectModelExternalSource? {
    val externalProjectSystemRegistry = ExternalProjectSystemRegistry.getInstance()
    for (module in ModuleManagerImpl.getInstanceImpl(project).modules) {
      externalProjectSystemRegistry.getExternalSource(module)?.let {
        return it
      }
    }
    return null
  }
}

fun getFilteredModuleList(modules: Array<Module>, isExternal: Boolean): List<Module> {
  val externalProjectSystemRegistry = ExternalProjectSystemRegistry.getInstance()
  return modules.filter { (externalProjectSystemRegistry.getExternalSource(it) != null) == isExternal }
}