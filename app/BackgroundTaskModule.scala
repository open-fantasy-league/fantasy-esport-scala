import play.api.inject.{SimpleModule, _}

class BackgroundTaskModule extends SimpleModule(bind[v1.tasks.BackgroundDraftTask].toSelf.eagerly())