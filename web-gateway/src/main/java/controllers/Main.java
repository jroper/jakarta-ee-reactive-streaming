package controllers;

import com.example.auction.user.api.UserService;
import com.lightbend.lagom.javadsl.client.cdi.LagomServiceClient;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Result;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class Main extends AbstractController {

    private HttpExecutionContext ec;

    @Inject
    public Main(MessagesApi messagesApi, @LagomServiceClient UserService userService, HttpExecutionContext ec) {
        super(messagesApi, userService);
        this.ec = ec;
    }

    public CompletionStage<Result> index() {
        return withUser(ctx(), userId ->
                loadNav(userId).thenApplyAsync(nav ->
                                ok(views.html.index.render(nav)),
                        ec.current())
        );
    }

}
