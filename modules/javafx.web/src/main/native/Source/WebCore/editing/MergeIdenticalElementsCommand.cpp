/*
 * Copyright (C) 2005, 2008 Apple Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY APPLE INC. ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL APPLE INC. OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include "config.h"
#include "MergeIdenticalElementsCommand.h"

#include "Element.h"

namespace WebCore {

MergeIdenticalElementsCommand::MergeIdenticalElementsCommand(PassRefPtr<Element> first, PassRefPtr<Element> second)
    : SimpleEditCommand(first->document())
    , m_element1(first)
    , m_element2(second)
{
    ASSERT(m_element1);
    ASSERT(m_element2);
    ASSERT(m_element1->nextSibling() == m_element2);
}

void MergeIdenticalElementsCommand::doApply()
{
    if (m_element1->nextSibling() != m_element2 || !m_element1->hasEditableStyle() || !m_element2->hasEditableStyle())
        return;

    m_atChild = m_element2->firstChild();

    Vector<Ref<Node>> children;
    for (Node* child = m_element1->firstChild(); child; child = child->nextSibling())
        children.append(*child);

    for (auto& child : children)
        m_element2->insertBefore(child, m_atChild.get());

    m_element1->remove();
}

void MergeIdenticalElementsCommand::doUnapply()
{
    ASSERT(m_element1);
    ASSERT(m_element2);

    RefPtr<Node> atChild = WTFMove(m_atChild);

    auto* parent = m_element2->parentNode();
    if (!parent || !parent->hasEditableStyle())
        return;

    if (parent->insertBefore(*m_element1, m_element2.get()).hasException())
        return;

    Vector<Ref<Node>> children;
    for (Node* child = m_element2->firstChild(); child && child != atChild; child = child->nextSibling())
        children.append(*child);

    for (auto& child : children)
        m_element1->appendChild(child);
}

#ifndef NDEBUG
void MergeIdenticalElementsCommand::getNodesInCommand(HashSet<Node*>& nodes)
{
    addNodeAndDescendants(m_element1.get(), nodes);
    addNodeAndDescendants(m_element2.get(), nodes);
}
#endif

} // namespace WebCore
