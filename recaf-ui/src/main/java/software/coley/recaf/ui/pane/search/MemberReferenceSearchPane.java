package software.coley.recaf.ui.pane.search;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.search.query.Query;
import software.coley.recaf.services.search.query.ReferenceQuery;
import software.coley.recaf.services.workspace.WorkspaceManager;

/**
 * Member declaration search pane.
 *
 * @author Matt Coley
 * @see MemberDeclarationSearchPane
 */
@Dependent
public class MemberReferenceSearchPane extends AbstractMemberSearchPane {
	@Inject
	public MemberReferenceSearchPane(@Nonnull WorkspaceManager workspaceManager,
	                                 @Nonnull SearchService searchService,
	                                 @Nonnull CellConfigurationService configurationService,
	                                 @Nonnull Actions actions,
	                                 @Nonnull StringPredicateProvider stringPredicateProvider) {
		super(workspaceManager, searchService, configurationService, actions, stringPredicateProvider);
	}

	@Nonnull
	@Override
	protected Query newQuery(@Nullable StringPredicate ownerPredicate,
	                         @Nullable StringPredicate namePredicate,
	                         @Nullable StringPredicate descPredicate) {
		return new ReferenceQuery(ownerPredicate, namePredicate, descPredicate);
	}
}
