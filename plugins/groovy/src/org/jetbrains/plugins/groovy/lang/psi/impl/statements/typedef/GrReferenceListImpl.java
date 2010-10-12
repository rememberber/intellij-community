/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package org.jetbrains.plugins.groovy.lang.psi.impl.statements.typedef;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.StubBasedPsiElement;
import com.intellij.psi.stubs.IStubElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrReferenceList;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrCodeReferenceElement;
import org.jetbrains.plugins.groovy.lang.psi.impl.GrClassReferenceType;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyBaseElementImpl;
import org.jetbrains.plugins.groovy.lang.psi.stubs.GrReferenceListStub;

import java.util.ArrayList;

/**
 * @author Maxim.Medvedev
 */
public abstract class GrReferenceListImpl extends GroovyBaseElementImpl<GrReferenceListStub>
  implements StubBasedPsiElement<GrReferenceListStub>, GrReferenceList {

  private PsiClassType[] cachedTypes = null;

  public GrReferenceListImpl(@NotNull ASTNode node) {
    super(node);
  }

  public GrReferenceListImpl(final GrReferenceListStub stub, IStubElementType elementType) {
    super(stub, elementType);
  }

  @NotNull
  public GrCodeReferenceElement[] getReferenceElements() {
    return findChildrenByClass(GrCodeReferenceElement.class);
  }

  @NotNull
  @Override
  public PsiClassType[] getReferenceTypes() {
    if (cachedTypes == null) {
      final ArrayList<PsiClassType> types = new ArrayList<PsiClassType>();
      for (GrCodeReferenceElement ref : getReferenceElements()) {
        types.add(new GrClassReferenceType(ref));
      }
      cachedTypes = types.toArray(new PsiClassType[types.size()]);
    }
    return cachedTypes;
  }

  @Override
  public void subtreeChanged() {
    cachedTypes = null;
  }
}
