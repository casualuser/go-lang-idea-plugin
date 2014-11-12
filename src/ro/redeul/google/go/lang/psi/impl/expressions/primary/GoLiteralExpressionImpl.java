package ro.redeul.google.go.lang.psi.impl.expressions.primary;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveState;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.util.Function;
import org.jetbrains.annotations.NotNull;
import ro.redeul.google.go.lang.psi.GoPsiElement;
import ro.redeul.google.go.lang.psi.declarations.GoConstDeclaration;
import ro.redeul.google.go.lang.psi.declarations.GoVarDeclaration;
import ro.redeul.google.go.lang.psi.expressions.GoExpr;
import ro.redeul.google.go.lang.psi.expressions.literals.GoLiteral;
import ro.redeul.google.go.lang.psi.expressions.literals.GoLiteralFunction;
import ro.redeul.google.go.lang.psi.expressions.literals.GoLiteralIdentifier;
import ro.redeul.google.go.lang.psi.expressions.literals.composite.GoLiteralComposite;
import ro.redeul.google.go.lang.psi.expressions.primary.GoLiteralExpression;
import ro.redeul.google.go.lang.psi.impl.expressions.GoExpressionBase;
import ro.redeul.google.go.lang.psi.statements.GoForWithRangeAndVarsStatement;
import ro.redeul.google.go.lang.psi.statements.switches.GoSwitchTypeClause;
import ro.redeul.google.go.lang.psi.statements.switches.GoSwitchTypeGuard;
import ro.redeul.google.go.lang.psi.statements.switches.GoSwitchTypeStatement;
import ro.redeul.google.go.lang.psi.toplevel.*;
import ro.redeul.google.go.lang.psi.types.GoPsiType;
import ro.redeul.google.go.lang.psi.typing.GoType;
import ro.redeul.google.go.lang.psi.typing.GoTypeConstant;
import ro.redeul.google.go.lang.psi.typing.GoTypes;
import ro.redeul.google.go.lang.psi.utils.GoPsiScopesUtil;
import ro.redeul.google.go.lang.psi.utils.GoPsiUtils;
import ro.redeul.google.go.lang.psi.visitors.GoElementVisitor;
import ro.redeul.google.go.lang.psi.visitors.GoElementVisitorWithData;
import ro.redeul.google.go.lang.stubs.GoNamesCache;

import static ro.redeul.google.go.lang.psi.typing.GoTypes.getInstance;

public class GoLiteralExpressionImpl extends GoExpressionBase
        implements GoLiteralExpression {

    public GoLiteralExpressionImpl(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public void accept(GoElementVisitor visitor) {
        visitor.visitLiteralExpression(this);
    }

    @Override
    public GoLiteral getLiteral() {
        return findChildByClass(GoLiteral.class);
    }

    private static class LiteralTypeCalculator
            implements Function<GoLiteralExpressionImpl, GoType[]> {
        @Override
        public GoType[] fun(GoLiteralExpressionImpl expression) {
            GoLiteral literal = expression.getLiteral();
            if (literal == null)
                return GoType.EMPTY_ARRAY;

            final GoTypes types = getInstance(expression.getProject());
            GoNamesCache namesCache = GoNamesCache.getInstance(expression.getProject());

            switch (literal.getType()) {
                case Bool:
                    return new GoType[]{GoTypes.constant(GoTypeConstant.Kind.Boolean, literal.getValue())};
                case Int:
                    return new GoType[]{GoTypes.constant(GoTypeConstant.Kind.Integer, literal.getValue())};
                case Float:
                    return new GoType[]{GoTypes.constant(GoTypeConstant.Kind.Float, literal.getValue())};
                case Char:
                    return new GoType[]{GoTypes.constant(GoTypeConstant.Kind.Rune, literal.getValue())};
                case ImaginaryInt:
                case ImaginaryFloat:
                    return new GoType[]{GoTypes.constant(GoTypeConstant.Kind.Complex, literal.getValue())};

                case RawString:
                case InterpretedString:
                    return new GoType[]{GoTypes.constant(GoTypeConstant.Kind.String, literal.getValue())};

                case Function:
                    return new GoType[]{GoTypes.fromPsi((GoLiteralFunction) literal)};

                case Identifier:
                    final GoLiteralIdentifier identifier = (GoLiteralIdentifier) literal;

                    if (identifier.isNil())
                        return new GoType[]{GoType.Nil};

                    if (identifier.isIota())
                        return new GoType[]{GoTypes.constant(GoTypeConstant.Kind.Integer, identifier.getIotaValue())};

                    GoPsiElement resolved = GoPsiUtils.resolveSafely(identifier, GoPsiElement.class);
                    if (resolved == null) {
                        return GoType.EMPTY_ARRAY;
                    }

                    return resolved.accept(new GoElementVisitorWithData<GoType[]>(GoType.EMPTY_ARRAY) {

                        GoLiteralIdentifier resolvedIdent = null;

                        @Override
                        public void visitImportDeclaration(GoImportDeclaration declaration) {
                            setData(GoTypes.getPackageType(declaration));
                        }

                        @Override
                        public void visitFunctionDeclaration(GoFunctionDeclaration declaration) {
                            setData(new GoType[]{GoTypes.fromPsi(declaration)});
                        }

                        @Override
                        public void visitTypeSpec(GoTypeSpec typeSpec) {
                            setData(new GoType[]{GoTypes.fromPsi(typeSpec.getType())});
                        }

                        @Override
                        public void visitLiteralIdentifier(GoLiteralIdentifier identifier) {
                            this.resolvedIdent = identifier;
                            ((GoPsiElement) identifier.getParent()).accept(this);
                        }

                        @Override
                        public void visitVarDeclaration(GoVarDeclaration declaration) {
                            if (resolvedIdent != null) {
                                GoType identifierType = declaration.getIdentifierType(resolvedIdent);

                                if (identifierType != null)
                                    setData(new GoType[]{identifierType});
                            }
                        }

                        @Override
                        public void visitConstDeclaration(GoConstDeclaration declaration) {
                            if (resolvedIdent != null) {
                                GoType declaredType = types.fromPsiType(declaration.getIdentifiersType());
                                GoExpr expr = declaration.getExpression(resolvedIdent);

                                if ( expr != null ) {
                                    GoType[] exprType = expr.getType();

                                    if ((exprType.length != 1 || !(exprType[0] instanceof GoTypeConstant)))
                                        setData(new GoType[]{declaredType});

                                    if (exprType.length == 1 && exprType[0] instanceof GoTypeConstant) {
                                        GoTypeConstant constant = (GoTypeConstant) exprType[0];
                                        if ( declaredType != GoType.Unknown)
                                            setData(new GoType[]{GoTypes.constant(constant.getKind(), constant.getValue(), declaredType)});
                                        else
                                            setData(new GoType[] {constant});
                                    }
                                }
                            }
                        }

                        @Override
                        public void visitFunctionParameter(GoFunctionParameter parameter) {
                            GoPsiType typeForBody = parameter.getTypeForBody();
                            if (typeForBody != null)
                                setData(new GoType[]{GoTypes.fromPsi(typeForBody)});
                        }

                        @Override
                        public void visitMethodReceiver(GoMethodReceiver receiver) {
                            GoPsiType type = receiver.getType();
                            if (type != null)
                                setData(new GoType[] { GoTypes.fromPsi(type)});
                        }


                        @Override
                        public void visitSwitchTypeGuard(GoSwitchTypeGuard typeGuard) {
                            GoSwitchTypeStatement switchStatement = (GoSwitchTypeStatement) typeGuard.getParent();

                            TextRange litRange = identifier.getTextRange();
                            for (GoSwitchTypeClause clause : switchStatement.getClauses()) {
                                TextRange clauseTextRange = clause.getTextRange();
                                if (clauseTextRange.contains(litRange)) {
                                    setData(GoTypes.fromPsiType(clause.getTypes()));
                                }
                            }
                        }

                        @Override
                        public void visitForWithRangeAndVars(GoForWithRangeAndVarsStatement statement) {
                            if ( resolvedIdent != null ) {
                                if (statement.getKey() == resolvedIdent) {
                                    setData(statement.getKeyType());
                                } else if (statement.getValue() == resolvedIdent) {
                                    setData(statement.getValueType());
                                }
                            }
                        }
                    });

                case Composite:
                    GoLiteralComposite composite = (GoLiteralComposite) literal;
                    GoPsiType literalType = composite.getLiteralType();
                    if (literalType == null) {
                        return GoType.EMPTY_ARRAY;
                    }
                    return new GoType[]{
                            GoTypes.fromPsi(literalType)
                    };

                default:
                    return GoType.EMPTY_ARRAY;
            }
        }
    }

    private static final LiteralTypeCalculator TYPE_CALCULATOR = new LiteralTypeCalculator();

    @NotNull
    @Override
    public GoType[] getType() {
//        return GoPsiManager.getInstance(getProject()).getType(this, TYPE_CALCULATOR);
        return TYPE_CALCULATOR.fun(this);
    }

    @Override
    public boolean isConstantExpression() {
        GoLiteral literal = getLiteral();
        if (literal == null)
            return true;

        switch (literal.getType()) {
            case Bool:
            case Char:
            case Float:
            case ImaginaryFloat:
            case ImaginaryInt:
            case Int:
            case InterpretedString:
            case RawString:
                return true;
            case Identifier:
                GoLiteralIdentifier identifier = (GoLiteralIdentifier) literal;

                if (identifier.isIota() || identifier.isNil()) {
                    return true;
                }

                GoLiteralIdentifier resolved = GoPsiUtils.resolveSafely(identifier, GoLiteralIdentifier.class);

                if (resolved == null)
                    return false;

                PsiElement parent = resolved.getParent();
                if (parent instanceof GoConstDeclaration) {
                    GoConstDeclaration constDeclaration = (GoConstDeclaration) parent;
                    GoExpr expr = constDeclaration.getExpression(resolved);
                    return expr == null || expr.isConstantExpression();
                }

        }

        return false;
    }

    @Override
    public boolean processDeclarations(@NotNull PsiScopeProcessor processor,
                                       @NotNull ResolveState state,
                                       PsiElement lastParent,
                                       @NotNull PsiElement place) {
        return GoPsiScopesUtil.walkChildrenScopes(this, processor, state, lastParent, place);
    }

    @Override
    protected PsiReference[] defineReferences() {
        return PsiReference.EMPTY_ARRAY;
    }


    /*
    @NotNull
    @Override
    public PsiReference[] getReferences() {

//        if (BuiltinCallOrConversionReference.MATCHER.accepts(this)) {
//            if (getLiteral().getText().matches("print|println"))
//                return refs(PsiReference.EMPTY_ARRAY);
//
//            return refs(new BuiltinCallOrConversionReference(this));
//        }
//
//        if (CallOrConversionReference.MATCHER.accepts(this))
//            return refs(
//                    new CallOrConversionReference(this),
//                    new VarOrConstReference((GoLiteralIdentifier) this.getLiteral()));

        return super.getReferences();
    }
*/
}
