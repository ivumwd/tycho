/*******************************************************************************
 * Copyright (c) 2010, 2023 SAP SE and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     SAP SE - initial API and implementation
 *     Hannes Wellmann - Assemble repository for all environments in one pass
 *     Hannes Wellmann - Implement user-defined filtering and filtering based on relevance for automatically added repo-references
 *******************************************************************************/
package org.eclipse.tycho.p2tools;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.equinox.internal.p2.metadata.IRequiredCapability;
import org.eclipse.equinox.internal.p2.metadata.InstallableUnit;
import org.eclipse.equinox.internal.p2.metadata.RequiredCapability;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.internal.repository.tools.SlicingOptions;
import org.eclipse.equinox.p2.metadata.IArtifactKey;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.IRequirement;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.metadata.expression.IMatchExpression;
import org.eclipse.equinox.p2.query.CollectionResult;
import org.eclipse.equinox.p2.query.IQueryable;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.IRepository;
import org.eclipse.equinox.p2.repository.IRepositoryManager;
import org.eclipse.equinox.p2.repository.IRepositoryReference;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepository;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.tycho.TargetPlatform;
import org.eclipse.tycho.p2.tools.DestinationRepositoryDescriptor;
import org.eclipse.tycho.p2.tools.RepositoryReference;
import org.eclipse.tycho.p2maven.ListCompositeArtifactRepository;
import org.eclipse.tycho.p2tools.copiedfromp2.PermissiveSlicer;
import org.eclipse.tycho.p2tools.copiedfromp2.RepositoryDescriptor;
import org.eclipse.tycho.p2tools.copiedfromp2.Slicer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TychoMirrorApplication extends org.eclipse.tycho.p2tools.copiedfromp2.MirrorApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(TychoMirrorApplication.class);
    private static final String SOURCE_SUFFIX = ".source";
    private static final String FEATURE_GROUP = ".feature.group";
    private final DestinationRepositoryDescriptor destination;
    private boolean includeAllSource;
    private boolean includeRequiredBundles;
    private boolean includeRequiredFeatures;
    private boolean filterProvided;
    private boolean addOnlyProvidingRepoReferences;
    private TargetPlatform targetPlatform;

    public TychoMirrorApplication(IProvisioningAgent agent, DestinationRepositoryDescriptor destination) {
        super(agent);
        this.destination = destination;
        this.removeAddedRepositories = false;
    }

    @Override
    protected IArtifactRepository initializeDestination(RepositoryDescriptor toInit, IArtifactRepositoryManager mgr)
            throws ProvisionException {
        IArtifactRepository result = super.initializeDestination(toInit, mgr);
        Map<String, String> extraArtifactRepositoryProperties = destination.getExtraArtifactRepositoryProperties();
        if (!extraArtifactRepositoryProperties.isEmpty()) {
            result.executeBatch(nil -> {
                extraArtifactRepositoryProperties.forEach(result::setProperty);
            }, null);
        }
        return result;
    }

    @Override
    public IArtifactRepository getCompositeArtifactRepository() throws ProvisionException {
        IArtifactRepository repository = super.getCompositeArtifactRepository();
        if (targetPlatform != null) {
            return new ListCompositeArtifactRepository(List.of(repository, targetPlatform.getArtifactRepository()),
                    agent);
        }
        return repository;
    }

    @Override
    protected Slicer createSlicer(SlicingOptions options) throws ProvisionException {
        List<Map<String, String>> filters = getContextFilters();
        List<IInstallableUnit> selectionContexts = filters.stream().map(InstallableUnit::contextIU).toList();
        boolean includeOptionalDependencies = options.includeOptionalDependencies();
        boolean onlyFilteredRequirements = options.followOnlyFilteredRequirements();
        boolean considerFilter = filters.stream().anyMatch(f -> f.size() > 1);
        boolean evalFilterTo = options.forceFilterTo();
        IMetadataRepository repository = getCompositeMetadataRepository();
        boolean considerOnlyStrictDependency = options.considerStrictDependencyOnly();
        return new PermissiveSlicer(repository, filters.get(0), includeOptionalDependencies,
                options.isEverythingGreedy(), evalFilterTo, considerOnlyStrictDependency, onlyFilteredRequirements) {
            @Override
            protected boolean isApplicable(IInstallableUnit iu, IRequirement req) {
                if ((includeRequiredBundles || includeRequiredFeatures) && QueryUtil.isGroup(iu)
                        && req instanceof IRequiredCapability capability
                        && IInstallableUnit.NAMESPACE_IU_ID.equals(capability.getNamespace())) {
                    boolean isFeature = capability.getName().endsWith(FEATURE_GROUP);
                    if ((isFeature && includeRequiredFeatures) || (!isFeature && includeRequiredBundles)) {
                        if (!includeOptionalDependencies && req.getMin() == 0) {
                            return false;
                        }
                        IMatchExpression<IInstallableUnit> filter = req.getFilter();
                        if (onlyFilteredRequirements && filter == null) {
                            return false;
                        }
                        return !considerFilter || filter == null || matchesSelectionContext(filter);
                    }
                }
                return isApplicable(req);
            }

            @Override
            protected boolean isApplicable(IRequirement req) {
                //Every filter in this method needs to continue except when the filter does not pass
                if (!includeOptionalDependencies && req.getMin() == 0) {
                    return false;
                }
                if (considerOnlyStrictDependency && !RequiredCapability.isStrictVersionRequirement(req.getMatches())) {
                    return false;
                }
                //deal with filters
                IMatchExpression<IInstallableUnit> filter = req.getFilter();
                if (considerFilter) {
                    if (onlyFilteredRequirements && filter == null) {
                        return false;
                    }
                    return filter == null || matchesSelectionContext(filter);
                }
                return filter == null ? !onlyFilteredRequirements : evalFilterTo;
            }

            @Override
            protected boolean isApplicable(IInstallableUnit iu) {
                if (considerFilter) {
                    IMatchExpression<IInstallableUnit> filter = iu.getFilter();
                    return filter == null || matchesSelectionContext(filter);
                }
                return iu.getFilter() == null || evalFilterTo;
            }

            private boolean matchesSelectionContext(IMatchExpression<IInstallableUnit> filter) {
                return selectionContexts.stream().anyMatch(filter::isMatch);
            }

            @Override
            public IQueryable<IInstallableUnit> slice(Collection<IInstallableUnit> ius, IProgressMonitor monitor) {
                IQueryable<IInstallableUnit> slice = super.slice(ius, monitor);
                if (includeAllSource && targetPlatform != null) {
                    Set<IInstallableUnit> collected = slice.query(QueryUtil.ALL_UNITS, null).toSet();
                    Set<IInstallableUnit> result = new HashSet<>(collected);
                    var allUnits = targetPlatform.getMetadataRepository().query(QueryUtil.ALL_UNITS, null);
                    Map<String, List<IInstallableUnit>> sourceIus = allUnits.stream()
                            .filter(iu -> iu.getId().endsWith(SOURCE_SUFFIX))
                            .collect(groupingBy(IInstallableUnit::getId));
                    for (IInstallableUnit iu : collected) {
                        String id = iu.getId();
                        String sourceId = id.endsWith(FEATURE_GROUP)
                                ? id.substring(id.length() - FEATURE_GROUP.length()) + SOURCE_SUFFIX
                                : id + SOURCE_SUFFIX;
                        List<IInstallableUnit> sourceUnits = sourceIus.get(sourceId);
                        if (sourceUnits != null) {
                            sourceUnits.stream().filter(su -> su.getVersion().equals(iu.getVersion())) //
                                    .findFirst().ifPresent(result::add);
                        }
                    }
                    return new CollectionResult<>(result);
                }
                return slice;
            }

            @Override
            protected Stream<IInstallableUnit> selectIUsForRequirement(IQueryable<IInstallableUnit> queryable,
                    IRequirement req) {
                Stream<IInstallableUnit> stream = super.selectIUsForRequirement(queryable, req);
                if (targetPlatform == null) {
                    return stream;
                }
                List<IInstallableUnit> list = stream.toList();
                if (list.isEmpty() && req.getMin() > 0) {
                    // It is possible that an IU is not visible to the slicer (e.g. dynamic category produced by a categorx.xml)
                    // In such case we additionally try to query the full target platform here.
                    return selectHighestIUsForRequirement(targetPlatform.getMetadataRepository(), req);
                }
                return list.stream();
            }

            protected Stream<IInstallableUnit> selectHighestIUsForRequirement(IQueryable<IInstallableUnit> queryable,
                    IRequirement req) {
                //first group by ID
                Map<String, List<IInstallableUnit>> groupById = queryable
                        .query(QueryUtil.createMatchQuery(req.getMatches()), null).stream().filter(this::isApplicable)
                        .collect(Collectors.groupingBy(IInstallableUnit::getId));
                //now select the max of items in each group with the same id
                return groupById.values().stream().flatMap(list -> list.stream()
                        .sorted(Comparator.comparing(IInstallableUnit::getVersion).reversed()).limit(req.getMax()));
            }
        };
    }

    @Override
    protected IMetadataRepository initializeDestination(RepositoryDescriptor toInit, IMetadataRepositoryManager mgr)
            throws ProvisionException {
        IMetadataRepository result = super.initializeDestination(toInit, mgr);
        var refs = Stream.of(destination.getRepositoryReferences(), destination.getFilterableRepositoryReferences())
                .flatMap(List::stream).flatMap(TychoMirrorApplication::toSpiRepositoryReferences).toList();
        result.addReferences(refs);
        return result;
    }

    private static Stream<org.eclipse.equinox.p2.repository.spi.RepositoryReference> toSpiRepositoryReferences(
            RepositoryReference rr) {
        return Stream.of(IRepository.TYPE_METADATA, IRepository.TYPE_ARTIFACT).map(type -> {
            URI location = rr.locationURINormalized();
            int options = rr.enable() ? IRepository.ENABLED : IRepository.NONE;
            return new org.eclipse.equinox.p2.repository.spi.RepositoryReference(location, rr.name(), type, options);
        });
    }

    @Override
    protected void finalizeRepositories() {
        IMetadataRepository repository = getDestinationMetadataRepository();
        if (repository != null) {
            Collection<IRepositoryReference> references = repository.getReferences();
            if (!references.isEmpty()) {
                LOGGER.info("Adding references to the following repositories:");
                references.stream().map(r -> r.getLocation()).distinct().forEach(loc -> LOGGER.info("  {}", loc));
            }
        }
        super.finalizeRepositories();
    }

    @Override
    protected List<IArtifactKey> collectArtifactKeys(Collection<IInstallableUnit> ius, IProgressMonitor monitor)
            throws ProvisionException {
        List<IArtifactKey> keys = super.collectArtifactKeys(ius, monitor);
        if (isFilterProvidedItems()) {
            removeProvidedItems(keys, getArtifactRepositoryManager(), IRepository.TYPE_ARTIFACT, monitor);
        }
        return keys;
    }

    @Override
    protected Set<IInstallableUnit> collectUnits(IQueryable<IInstallableUnit> slice, IProgressMonitor monitor)
            throws ProvisionException {
        Set<IInstallableUnit> units = super.collectUnits(slice, monitor);
        if (isFilterProvidedItems()) {
            Map<String, List<Version>> fullRepositoryContent = units.stream()
                    .collect(groupingBy(IInstallableUnit::getId, mapping(IInstallableUnit::getVersion, toList())));

            List<IRepository<IInstallableUnit>> metadataRepositories = removeProvidedItems(units,
                    getMetadataRepositoryManager(), IRepository.TYPE_METADATA, monitor);

            if (addOnlyProvidingRepoReferences) {
                Set<URI> removableReferences = destination.getFilterableRepositoryReferences().stream()
                        .map(RepositoryReference::locationURINormalized).collect(Collectors.toSet());
                destination.getRepositoryReferences().stream().map(RepositoryReference::locationURINormalized)
                        .forEach(removableReferences::remove); // keep reference if explicitly added to the repository
                if (!removableReferences.isEmpty()) {
                    // Assume that for all units that correspond to artifacts the metadata either has a co-located artifact repository or a references to to one that contains it.
                    removeNotProvidingReferences(fullRepositoryContent, metadataRepositories, removableReferences);
                }
            }
        }
        return units;
    }

    private boolean isFilterProvidedItems() {
        return filterProvided && !destinationMetadataRepository.getReferences().isEmpty();
    }

    private <T> List<IRepository<T>> removeProvidedItems(Collection<T> allElements, IRepositoryManager<T> repoManager,
            int repositoryType, IProgressMonitor monitor) throws ProvisionException {
        List<IRepository<T>> referencedRepositories = new ArrayList<>();
        for (IRepositoryReference reference : destinationMetadataRepository.getReferences()) {
            if (reference.getType() != repositoryType) {
                continue;
            }
            try {
                URI location = reference.getLocation();
                IRepository<T> repository = repoManager.loadRepository(location, monitor);
                referencedRepositories.add(repository);
            } catch (IllegalArgumentException e) {
                if (e.getCause() instanceof URISyntaxException uriException) {
                    throw new ProvisionException("Can't parse referenced URI!", uriException);
                } else {
                    throw e;
                }
            }
        }
        allElements.removeIf(e -> referencedRepositories.stream().anyMatch(repo -> repo.contains(e)));
        return referencedRepositories;
    }

    private void removeNotProvidingReferences(Map<String, List<Version>> fullRepositoryContent,
            List<IRepository<IInstallableUnit>> metadataRepositories, Set<URI> removableReferenceURIs) {
        Map<URI, Set<IInstallableUnit>> usedRepositoryItems = new HashMap<>();
        for (IRepository<IInstallableUnit> repo : metadataRepositories) {
            Set<IInstallableUnit> usedRepoContent = repo.query(QueryUtil.ALL_UNITS, null).stream()
                    .filter(a -> fullRepositoryContent.getOrDefault(a.getId(), List.of()).contains(a.getVersion()))
                    .collect(Collectors.toSet());
            usedRepositoryItems.put(repo.getLocation(), usedRepoContent);
        }
        // Remove filterable references that contribute nothing or whose relevant content is also provided by another repo
        usedRepositoryItems.entrySet().removeIf(repo -> {
            if (!removableReferenceURIs.contains(repo.getKey())) {
                return false;
            }
            Set<IInstallableUnit> usedContent = repo.getValue();
            return usedContent.isEmpty()
                    || usedRepositoryItems.entrySet().stream().filter(e -> e != repo).map(Entry::getValue)
                            .anyMatch(other -> other.size() >= usedContent.size() && other.containsAll(usedContent));
        });
        IMetadataRepository repository = getDestinationMetadataRepository();
        List<IRepositoryReference> discardedReferences = repository.getReferences().stream()
                .filter(rr -> !usedRepositoryItems.keySet().contains(rr.getLocation())).toList();
        repository.removeReferences(discardedReferences);
    }

    public void setIncludeSources(boolean includeAllSource, TargetPlatform targetPlatform) {
        this.includeAllSource = includeAllSource;
        this.targetPlatform = targetPlatform;
    }

    public void setIncludeRequiredBundles(boolean includeRequiredBundles) {
        this.includeRequiredBundles = includeRequiredBundles;
    }

    public void setIncludeRequiredFeatures(boolean includeRequiredFeatures) {
        this.includeRequiredFeatures = includeRequiredFeatures;
    }

    public void setFilterProvided(boolean filterProvided) {
        this.filterProvided = filterProvided;
    }

    public void setAddOnlyProvidingRepoReferences(boolean addOnlyProvidingRepoReferences) {
        this.addOnlyProvidingRepoReferences = addOnlyProvidingRepoReferences;
    }

}
