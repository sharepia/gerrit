// Copyright (C) 2017 The Android Open Source Project
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

package com.google.gerrit.server.restapi.change;

import static com.google.gerrit.extensions.conditions.BooleanCondition.and;
import static com.google.gerrit.extensions.conditions.BooleanCondition.or;

import com.google.gerrit.common.TimeUtil;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Change.Status;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.WorkInProgressOp;
import com.google.gerrit.server.change.WorkInProgressOp.Input;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.RetryHelper;
import com.google.gerrit.server.update.RetryingRestModifyView;
import com.google.gerrit.server.update.UpdateException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

@Singleton
public class SetReadyForReview extends RetryingRestModifyView<ChangeResource, Input, Response<?>>
    implements UiAction<ChangeResource> {
  private final WorkInProgressOp.Factory opFactory;
  private final Provider<ReviewDb> db;
  private final PermissionBackend permissionBackend;

  @Inject
  SetReadyForReview(
      RetryHelper retryHelper,
      WorkInProgressOp.Factory opFactory,
      Provider<ReviewDb> db,
      PermissionBackend permissionBackend) {
    super(retryHelper);
    this.opFactory = opFactory;
    this.db = db;
    this.permissionBackend = permissionBackend;
  }

  @Override
  protected Response<?> applyImpl(
      BatchUpdate.Factory updateFactory, ChangeResource rsrc, Input input)
      throws RestApiException, UpdateException, PermissionBackendException {
    Change change = rsrc.getChange();
    if (!rsrc.isUserOwner()
        && !permissionBackend.currentUser().test(GlobalPermission.ADMINISTRATE_SERVER)
        && !permissionBackend
            .currentUser()
            .project(rsrc.getProject())
            .test(ProjectPermission.WRITE_CONFIG)) {
      throw new AuthException("not allowed to set ready for review");
    }

    if (change.getStatus() != Status.NEW) {
      throw new ResourceConflictException("change is " + ChangeUtil.status(change));
    }

    if (!change.isWorkInProgress()) {
      throw new ResourceConflictException("change is not work in progress");
    }

    try (BatchUpdate bu =
        updateFactory.create(db.get(), rsrc.getProject(), rsrc.getUser(), TimeUtil.nowTs())) {
      bu.addOp(rsrc.getChange().getId(), opFactory.create(false, input));
      bu.execute();
      return Response.ok("");
    }
  }

  @Override
  public Description getDescription(ChangeResource rsrc) {
    return new Description()
        .setLabel("Start Review")
        .setTitle("Set Ready For Review")
        .setVisible(
            and(
                rsrc.getChange().getStatus() == Status.NEW && rsrc.getChange().isWorkInProgress(),
                or(
                    rsrc.isUserOwner(),
                    or(
                        permissionBackend
                            .currentUser()
                            .testCond(GlobalPermission.ADMINISTRATE_SERVER),
                        permissionBackend
                            .currentUser()
                            .project(rsrc.getProject())
                            .testCond(ProjectPermission.WRITE_CONFIG)))));
  }
}
