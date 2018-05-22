package com.example.auction.user.impl;

import akka.NotUsed;
import com.example.auction.pagination.PaginatedSequence;
import com.example.auction.user.api.User;
import com.example.auction.user.api.UserLogin;
import com.example.auction.user.api.UserRegistration;
import com.example.auction.user.api.UserService;
import com.lightbend.lagom.javadsl.api.ServiceCall;
import com.lightbend.lagom.javadsl.api.transport.NotFound;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRef;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRegistry;
import com.lightbend.lagom.javadsl.server.cdi.LagomService;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Optional;
import java.util.UUID;

@LagomService
@ApplicationScoped
public class UserServiceImpl implements UserService {

    private final PersistentEntityRegistry registry;
    private static final Integer DEFAULT_PAGE_SIZE = 10;
    private final UserRepository userRepository;

    @Inject
    public UserServiceImpl(PersistentEntityRegistry registry, UserRepository userRepository) {
        this.registry = registry;
        this.userRepository = userRepository;
    }


    @Override
    public ServiceCall<UserRegistration, User> createUser() {
        return user -> {
            UUID uuid = getUUIDFromEmail(user.getEmail());
            String password = PUserEntity.hashPassword(user.getPassword());
            PUser createdUser = new PUser(uuid, user.getName(), user.getEmail(), password);
            return entityRef(uuid)
                .ask(new PUserCommand.CreatePUser(user.getName(), user.getEmail(), password))
                .thenApply(done -> Mappers.toApi(Optional.ofNullable(createdUser)));
        };
    }

    private UUID getUUIDFromEmail(String email) {
        return UUID.nameUUIDFromBytes(email.toLowerCase().getBytes());
    }

    @Override
    public ServiceCall<NotUsed, User> getUser(UUID userId) {
        return request ->
            entityRef(userId)
                .ask(PUserCommand.GetPUser.INSTANCE)
                .thenApply(maybeUser -> {
                    User user = Mappers.toApi(((Optional<PUser>) maybeUser));
                    return user;
                });
    }

    @Override
    public ServiceCall<NotUsed, PaginatedSequence<User>> getUsers(Optional<Integer> pageNo, Optional<Integer> pageSize) {
        return req -> userRepository.getUsers(pageNo.orElse(0), pageSize.orElse(DEFAULT_PAGE_SIZE));
    }

    @Override
    public ServiceCall<UserLogin, String> login() {
        return req -> {
            UUID id = getUUIDFromEmail(req.getEmail());
            return entityRef(id).ask(PUserCommand.GetPUser.INSTANCE)
                .thenApply(maybeUser -> {
                        if (maybeUser.isPresent()) {
                            if (PUserEntity.checkPassword(req.getPassword(), maybeUser.get().getPasswordHash())) {
                                return maybeUser.get().getId().toString();
                            } else {
                                // TODO: replace with Forbidden.class
                                throw new NotFound("Email or password does not match ");
                            }
                        } else {
                            // TODO: replace with Forbidden.class
                            throw new NotFound("User not found");
                        }
                    }
                );
        };
    }

    private PersistentEntityRef<PUserCommand> entityRef(UUID id) {
        return entityRef(id.toString());
    }

    private PersistentEntityRef<PUserCommand> entityRef(String id) {
        return registry.refFor(PUserEntity.class, id);
    }

}
