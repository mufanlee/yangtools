/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangtools.yang.data.codec.gson;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nonnull;

import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.AugmentationIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.stream.NormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.model.api.AnyXmlSchemaNode;
import org.opendaylight.yangtools.yang.model.api.AugmentationSchema;
import org.opendaylight.yangtools.yang.model.api.AugmentationTarget;
import org.opendaylight.yangtools.yang.model.api.ChoiceCaseNode;
import org.opendaylight.yangtools.yang.model.api.ChoiceNode;
import org.opendaylight.yangtools.yang.model.api.ContainerSchemaNode;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.LeafListSchemaNode;
import org.opendaylight.yangtools.yang.model.api.LeafSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ListSchemaNode;

/**
 * A node which is composed of multiple simpler nodes.
 */
class CompositeNodeDataWithSchema extends AbstractNodeDataWithSchema {
    private static final Function<DataSchemaNode, QName> QNAME_FUNCTION = new Function<DataSchemaNode, QName>() {
        @Override
        public QName apply(@Nonnull final DataSchemaNode input) {
            return input.getQName();
        }
    };

    /**
     * nodes which were added to schema via augmentation and are present in data input
     */
    private final Map<AugmentationSchema, List<AbstractNodeDataWithSchema>> augmentationsToChild = new LinkedHashMap<>();

    /**
     * remaining data nodes (which aren't added via augment). Every of one them should have the same QName.
     */
    private final List<AbstractNodeDataWithSchema> children = new ArrayList<>();

    public CompositeNodeDataWithSchema(final DataSchemaNode schema) {
        super(schema);
    }

    public AbstractNodeDataWithSchema addChild(final Deque<DataSchemaNode> schemas) {
        Preconditions.checkArgument(!schemas.isEmpty(), "Expecting at least one schema");

        // Pop the first node...
        final DataSchemaNode schema = schemas.pop();
        if (schemas.isEmpty()) {
            // Simple, direct node
            return addChild(schema);
        }

        // The choice/case mess, reuse what we already popped
        final DataSchemaNode choiceCandidate = schema;
        Preconditions.checkArgument(choiceCandidate instanceof ChoiceNode,
            "Expected node of type ChoiceNode but was %s", choiceCandidate.getClass().getSimpleName());
        final ChoiceNode choiceNode = (ChoiceNode) choiceCandidate;

        final DataSchemaNode caseCandidate = schemas.pop();
        Preconditions.checkArgument(caseCandidate instanceof ChoiceCaseNode,
            "Expected node of type ChoiceCaseNode but was %s", caseCandidate.getClass().getSimpleName());
        final ChoiceCaseNode caseNode = (ChoiceCaseNode) caseCandidate;

        AugmentationSchema augSchema = null;
        if (choiceCandidate.isAugmenting()) {
            augSchema = findCorrespondingAugment(getSchema(), choiceCandidate);
        }

        // looking for existing choice
        final List<AbstractNodeDataWithSchema> childNodes;
        if (augSchema != null) {
            childNodes = augmentationsToChild.get(augSchema);
        } else {
            childNodes = children;
        }

        CompositeNodeDataWithSchema caseNodeDataWithSchema = findChoice(childNodes, choiceCandidate, caseCandidate);
        if (caseNodeDataWithSchema == null) {
            ChoiceNodeDataWithSchema choiceNodeDataWithSchema = new ChoiceNodeDataWithSchema(choiceNode);
            addChild(choiceNodeDataWithSchema);
            caseNodeDataWithSchema = choiceNodeDataWithSchema.addCompositeChild(caseNode);
        }

        return caseNodeDataWithSchema.addChild(schemas);
    }

    private AbstractNodeDataWithSchema addSimpleChild(final DataSchemaNode schema) {
        SimpleNodeDataWithSchema newChild = null;
        if (schema instanceof LeafSchemaNode) {
            newChild = new LeafNodeDataWithSchema(schema);
        } else if (schema instanceof AnyXmlSchemaNode) {
            newChild = new AnyXmlNodeDataWithSchema(schema);
        }

        if (newChild != null) {

            AugmentationSchema augSchema = null;
            if (schema.isAugmenting()) {
                augSchema = findCorrespondingAugment(getSchema(), schema);
            }
            if (augSchema != null) {
                addChildToAugmentation(augSchema, newChild);
            } else {
                addChild(newChild);
            }
            return newChild;
        }
        return null;
    }

    private void addChildToAugmentation(final AugmentationSchema augSchema, final AbstractNodeDataWithSchema newChild) {
        List<AbstractNodeDataWithSchema> childsInAugment = augmentationsToChild.get(augSchema);
        if (childsInAugment == null) {
            childsInAugment = new ArrayList<>();
            augmentationsToChild.put(augSchema, childsInAugment);
        }
        childsInAugment.add(newChild);
    }

    private CaseNodeDataWithSchema findChoice(final List<AbstractNodeDataWithSchema> childNodes, final DataSchemaNode choiceCandidate,
            final DataSchemaNode caseCandidate) {
        if (childNodes != null) {
            for (AbstractNodeDataWithSchema nodeDataWithSchema : childNodes) {
                if (nodeDataWithSchema instanceof ChoiceNodeDataWithSchema
                        && nodeDataWithSchema.getSchema().getQName().equals(choiceCandidate.getQName())) {
                    CaseNodeDataWithSchema casePrevious = ((ChoiceNodeDataWithSchema) nodeDataWithSchema).getCase();

                    Preconditions.checkArgument(casePrevious.getSchema().getQName().equals(caseCandidate.getQName()),
                        "Data from case %s are specified but other data from case %s were specified erlier. Data aren't from the same case.",
                        caseCandidate.getQName(), casePrevious.getSchema().getQName());

                    return casePrevious;
                }
            }
        }
        return null;
    }

    AbstractNodeDataWithSchema addCompositeChild(final DataSchemaNode schema) {
        CompositeNodeDataWithSchema newChild;
        if (schema instanceof ListSchemaNode) {
            newChild = new ListNodeDataWithSchema(schema);
        } else if (schema instanceof LeafListSchemaNode) {
            newChild = new LeafListNodeDataWithSchema(schema);
        } else if (schema instanceof ContainerSchemaNode) {
            newChild = new ContainerNodeDataWithSchema(schema);
        } else {
            newChild = new CompositeNodeDataWithSchema(schema);
        }
        addCompositeChild(newChild);
        return newChild;
    }

    void addCompositeChild(final CompositeNodeDataWithSchema newChild) {
        AugmentationSchema augSchema = findCorrespondingAugment(getSchema(), newChild.getSchema());
        if (augSchema != null) {
            addChildToAugmentation(augSchema, newChild);
        } else {
            addChild(newChild);
        }
    }

    private AbstractNodeDataWithSchema addChild(final DataSchemaNode schema) {
        AbstractNodeDataWithSchema newChild = addSimpleChild(schema);
        return newChild == null ? addCompositeChild(schema) : newChild;
    }

    public void addChild(final AbstractNodeDataWithSchema newChild) {
        children.add(newChild);
    }

    /**
     * Return a hint about how may children we are going to generate.
     * @return Size of currently-present node list.
     */
    protected final int childSizeHint() {
        return children.size();
    }

    /**
     * Tries to find in {@code parent} which is dealed as augmentation target node with QName as {@code child}. If such
     * node is found then it is returned, else null.
     */
    AugmentationSchema findCorrespondingAugment(final DataSchemaNode parent, final DataSchemaNode child) {
        if (parent instanceof AugmentationTarget) {
            for (AugmentationSchema augmentation : ((AugmentationTarget) parent).getAvailableAugmentations()) {
                DataSchemaNode childInAugmentation = augmentation.getDataChildByName(child.getQName());
                if (childInAugmentation != null) {
                    return augmentation;
                }
            }
        }
        return null;
    }

    @Override
    public void write(final NormalizedNodeStreamWriter writer) throws IOException {
        for (AbstractNodeDataWithSchema child : children) {
            child.write(writer);
        }
        for (Entry<AugmentationSchema, List<AbstractNodeDataWithSchema>> augmentationToChild : augmentationsToChild.entrySet()) {
            final List<AbstractNodeDataWithSchema> childsFromAgumentation = augmentationToChild.getValue();
            if (!childsFromAgumentation.isEmpty()) {
                writer.startAugmentationNode(toAugmentationIdentifier(augmentationToChild.getKey()));

                for (AbstractNodeDataWithSchema nodeDataWithSchema : childsFromAgumentation) {
                    nodeDataWithSchema.write(writer);
                }

                writer.endNode();
            }
        }
    }

    private static AugmentationIdentifier toAugmentationIdentifier(final AugmentationSchema schema) {
        final Collection<QName> qnames = Collections2.transform(schema.getChildNodes(), QNAME_FUNCTION);
        return new AugmentationIdentifier(ImmutableSet.copyOf(qnames));
    }
}
