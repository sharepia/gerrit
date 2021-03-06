// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.restapi.group;

import com.google.common.collect.ListMultimap;
import com.google.gerrit.common.data.GroupDescription;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.AcceptsCreate;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.NeedsParams;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestCollection;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.extensions.restapi.TopLevelResource;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.server.AnonymousUser;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupBackends;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupControl;
import com.google.gerrit.server.group.GroupResource;
import com.google.gerrit.server.group.InternalGroup;
import com.google.gerrit.server.group.InternalGroupDescription;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.util.Optional;

public class GroupsCollection
    implements RestCollection<TopLevelResource, GroupResource>,
        AcceptsCreate<TopLevelResource>,
        NeedsParams {
  private final DynamicMap<RestView<GroupResource>> views;
  private final Provider<ListGroups> list;
  private final Provider<QueryGroups> queryGroups;
  private final CreateGroup.Factory createGroup;
  private final GroupControl.Factory groupControlFactory;
  private final GroupBackend groupBackend;
  private final GroupCache groupCache;
  private final Provider<CurrentUser> self;

  private boolean hasQuery2;

  @Inject
  public GroupsCollection(
      DynamicMap<RestView<GroupResource>> views,
      Provider<ListGroups> list,
      Provider<QueryGroups> queryGroups,
      CreateGroup.Factory createGroup,
      GroupControl.Factory groupControlFactory,
      GroupBackend groupBackend,
      GroupCache groupCache,
      Provider<CurrentUser> self) {
    this.views = views;
    this.list = list;
    this.queryGroups = queryGroups;
    this.createGroup = createGroup;
    this.groupControlFactory = groupControlFactory;
    this.groupBackend = groupBackend;
    this.groupCache = groupCache;
    this.self = self;
  }

  @Override
  public void setParams(ListMultimap<String, String> params) throws BadRequestException {
    if (params.containsKey("query") && params.containsKey("query2")) {
      throw new BadRequestException("\"query\" and \"query2\" options are mutually exclusive");
    }

    // The --query2 option is defined in QueryGroups
    this.hasQuery2 = params.containsKey("query2");
  }

  @Override
  public RestView<TopLevelResource> list() throws ResourceNotFoundException, AuthException {
    final CurrentUser user = self.get();
    if (user instanceof AnonymousUser) {
      throw new AuthException("Authentication required");
    } else if (!(user.isIdentifiedUser())) {
      throw new ResourceNotFoundException();
    }

    if (hasQuery2) {
      return queryGroups.get();
    }

    return list.get();
  }

  @Override
  public GroupResource parse(TopLevelResource parent, IdString id)
      throws AuthException, ResourceNotFoundException {
    final CurrentUser user = self.get();
    if (user instanceof AnonymousUser) {
      throw new AuthException("Authentication required");
    } else if (!(user.isIdentifiedUser())) {
      throw new ResourceNotFoundException(id);
    }

    GroupDescription.Basic group = parseId(id.get());
    if (group == null) {
      throw new ResourceNotFoundException(id.get());
    }
    GroupControl ctl = groupControlFactory.controlFor(group);
    if (!ctl.isVisible()) {
      throw new ResourceNotFoundException(id);
    }
    return new GroupResource(ctl);
  }

  /**
   * Parses a group ID from a request body and returns the group.
   *
   * @param id ID of the group, can be a group UUID, a group name or a legacy group ID
   * @return the group
   * @throws UnprocessableEntityException thrown if the group ID cannot be resolved or if the group
   *     is not visible to the calling user
   */
  public GroupDescription.Basic parse(String id) throws UnprocessableEntityException {
    GroupDescription.Basic group = parseId(id);
    if (group == null || !groupControlFactory.controlFor(group).isVisible()) {
      throw new UnprocessableEntityException(String.format("Group Not Found: %s", id));
    }
    return group;
  }

  /**
   * Parses a group ID from a request body and returns the group if it is a Gerrit internal group.
   *
   * @param id ID of the group, can be a group UUID, a group name or a legacy group ID
   * @return the group
   * @throws UnprocessableEntityException thrown if the group ID cannot be resolved, if the group is
   *     not visible to the calling user or if it's an external group
   */
  public GroupDescription.Internal parseInternal(String id) throws UnprocessableEntityException {
    GroupDescription.Basic group = parse(id);
    if (group instanceof GroupDescription.Internal) {
      return (GroupDescription.Internal) group;
    }

    throw new UnprocessableEntityException(String.format("External Group Not Allowed: %s", id));
  }

  /**
   * Parses a group ID and returns the group without making any permission check whether the current
   * user can see the group.
   *
   * @param id ID of the group, can be a group UUID, a group name or a legacy group ID
   * @return the group, null if no group is found for the given group ID
   */
  public GroupDescription.Basic parseId(String id) {
    AccountGroup.UUID uuid = new AccountGroup.UUID(id);
    if (groupBackend.handles(uuid)) {
      GroupDescription.Basic d = groupBackend.get(uuid);
      if (d != null) {
        return d;
      }
    }

    // Might be a numeric AccountGroup.Id. -> Internal group.
    if (id.matches("^[1-9][0-9]*$")) {
      try {
        AccountGroup.Id groupId = AccountGroup.Id.parse(id);
        Optional<InternalGroup> group = groupCache.get(groupId);
        if (group.isPresent()) {
          return new InternalGroupDescription(group.get());
        }
      } catch (IllegalArgumentException e) {
        // Ignored
      }
    }

    // Might be a group name, be nice and accept unique names.
    GroupReference ref = GroupBackends.findExactSuggestion(groupBackend, id);
    if (ref != null) {
      GroupDescription.Basic d = groupBackend.get(ref.getUUID());
      if (d != null) {
        return d;
      }
    }

    return null;
  }

  @Override
  public CreateGroup create(TopLevelResource root, IdString name) throws RestApiException {
    return createGroup.create(name.get());
  }

  @Override
  public DynamicMap<RestView<GroupResource>> views() {
    return views;
  }
}
