package sqltyped

import scala.util.parsing.combinator._
import scala.reflect.runtime.universe.{Type, typeOf}

trait SqlParser extends RegexParsers with Ast.Unresolved with PackratParsers {
  import Ast._

  def parse(sql: String): ?[Statement] = parseAll(stmt, sql) match {
    case Success(r, q)  => Right(r)
    case err: NoSuccess => Left(err.msg)
  }

  lazy val stmt = (setStmt | selectStmt | insertStmt | updateStmt | deleteStmt | createStmt)

  lazy val selectStmt = select ~ from ~ opt(where) ~ opt(groupBy) ~ opt(orderBy) ~ opt(limit) <~ opt("for".i ~ "update".i) ^^ {
    case s ~ f ~ w ~ g ~ o ~ l => Select(s, f, w, g, o, l)
  }

  lazy val setOperator = ("union".i | "except".i | "intersect".i)

  lazy val setStmt = optParens(selectStmt) ~ setOperator ~ opt("all".i) ~ optParens(selectStmt) ~ opt(orderBy) ~ opt(limit) ^^ {
    case s1 ~ op ~ _ ~ s2 ~ o ~ l => SetStatement(s1, op, s2, o, l)
  }

  lazy val insertSyntax = insert ~> "into".i ~> table ~ opt(colNames) ~ (listValues | selectValues)

  lazy val insertStmt: Parser[Statement] = insertSyntax ^^ {
    case t ~ cols ~ vals => Insert(t, cols, vals)
  }

  lazy val colNames = "(" ~> repsep(ident, ",") <~ ")"

  lazy val listValues = "values".i ~> "(" ~> repsep(term, ",") <~ ")" ^^ ListedInput.apply

  lazy val selectValues = optParens(selectStmt) ^^ SelectedInput.apply

  lazy val updateStmt = update ~ rep1sep(table, ",") ~ "set".i ~ rep1sep(assignment, ",") ~ opt(where) ~ opt(orderBy) ~ opt(limit) ^^ {
    case _ ~ t ~ _ ~ a ~ w ~ o ~ l => Update(t, a, w, o, l)
  }

  def insert = "insert".i
  def update = "update".i

  lazy val assignment = column ~ "=" ~ term ^^ { case c ~ _ ~ t => (c, t) }

  lazy val deleteStmt = 
    "delete".i ~ opt(repsep(ident, ",")) ~ "from".i ~ rep1sep(table, ",") ~ opt(where) ^^ {
      case _ ~ _ ~ t ~ w => Delete(t, w)
    }

  lazy val createStmt = "create".i ^^^ Create[Option[String]]()

  lazy val select = "select".i ~> repsep((opt("distinct".i | "all".i) ~> named), ",")

  lazy val from = "from".i ~> rep1sep(tableReference, ",")

  lazy val tableReference: Parser[TableReference] = (
      joinedTable
    | derivedTable
    | table ^^ (t => ConcreteTable(t, Nil))
  )

  lazy val joinedTable = table ~ rep(joinSpec) ^^ { case t ~ j => ConcreteTable(t, j) }

  lazy val joinSpec = (crossJoin | qualifiedJoin)

  lazy val crossJoin = "cross".i ~ "join".i ~ optParens(tableReference) ^^ {
    case _ ~ _ ~ table => Join(table, None, "cross join")
  }

  lazy val qualifiedJoin = opt("left".i | "right".i) ~ opt("inner".i | "outer".i) ~ "join".i ~ optParens(tableReference) ~ opt("on".i ~> expr) ^^ {
    case side ~ joinType ~ _ ~ table ~ expr => 
      Join(table, expr, side.map(_ + " ").getOrElse("") + joinType.map(_ + " ").getOrElse("") + "join")
  }

  lazy val derivedTable = subselect ~ "as".i ~ ident ~ rep(joinSpec) ^^ { 
    case s ~ _ ~ a ~ j => DerivedTable(a, s.select, j)
  }

  lazy val table = ident ~ opt(opt("as".i) ~> ident) ^^ { case n ~ a => Table(n, a) }

  lazy val where = "where".i ~> expr ^^ Where.apply

  lazy val expr: PackratParser[Expr] = (comparison | parens | notExpr)* (
      "and".i ^^^ { (e1: Expr, e2: Expr) => And(e1, e2) } 
    | "or".i  ^^^ { (e1: Expr, e2: Expr) => Or(e1, e2) } 
  )

  lazy val parens: PackratParser[Expr] = "(" ~> expr  <~ ")"
  lazy val notExpr: PackratParser[Expr] = "not".i ~> expr ^^ Not.apply

  lazy val comparison: PackratParser[Comparison] = (
      term ~ "="  ~ term          ^^ { case lhs ~ _ ~ rhs => Comparison2(lhs, Eq, rhs) }
    | term ~ "!=" ~ term          ^^ { case lhs ~ _ ~ rhs => Comparison2(lhs, Neq, rhs) }
    | term ~ "<>" ~ term          ^^ { case lhs ~ _ ~ rhs => Comparison2(lhs, Neq, rhs) }
    | term ~ "<"  ~ term          ^^ { case lhs ~ _ ~ rhs => Comparison2(lhs, Lt, rhs) }
    | term ~ ">"  ~ term          ^^ { case lhs ~ _ ~ rhs => Comparison2(lhs, Gt, rhs) }
    | term ~ "<=" ~ term          ^^ { case lhs ~ _ ~ rhs => Comparison2(lhs, Le, rhs) }
    | term ~ ">=" ~ term          ^^ { case lhs ~ _ ~ rhs => Comparison2(lhs, Ge, rhs) }
    | term ~ "like".i ~ term      ^^ { case lhs ~ _ ~ rhs => Comparison2(lhs, Like, rhs) }
    | term ~ "in".i ~ (terms | subselect) ^^ { case lhs ~ _ ~ rhs => Comparison2(lhs, In, rhs) }
    | term ~ "not".i ~ "in".i ~ (terms | subselect) ^^ { case lhs ~ _ ~ _ ~ rhs => Comparison2(lhs, NotIn, rhs) }
    | term ~ "between".i ~ term ~ "and".i ~ term ^^ { case t1 ~ _ ~ t2 ~ _ ~ t3 => Comparison3(t1, Between, t2, t3) }
    | term ~ "not".i ~ "between".i ~ term ~ "and".i ~ term ^^ { case t1 ~ _ ~ _ ~ t2 ~ _ ~ t3 => Comparison3(t1, NotBetween, t2, t3) }
    | term <~ "is".i ~ "null".i           ^^ { t => Comparison1(t, IsNull) }
    | term <~ "is".i ~ "not".i ~ "null".i ^^ { t => Comparison1(t, IsNotNull) }
    | "exists".i ~> subselect             ^^ { t => Comparison1(t, Exists) }
    | "not" ~> "exists".i ~> subselect    ^^ { t => Comparison1(t, NotExists) }
  )

  lazy val subselect = "(" ~> selectStmt <~ ")" ^^ Subselect.apply

  lazy val term = (arith | simpleTerm)

  lazy val terms: PackratParser[Term] = "(" ~> repsep(term, ",") <~ ")" ^^ TermList.apply

  lazy val simpleTerm: PackratParser[Term] = (
      subselect
    | function
    | boolean
    | nullLit    ^^^ constNull
    | stringLit  ^^ constS
    | numericLit ^^ (n => if (n.contains(".")) constD(n.toDouble) else constL(n.toLong))
    | extraTerms
    | allColumns
    | column
    | "?"        ^^^ Input[Option[String]]()
  )

  lazy val named = (comparison | arith | simpleTerm) ~ opt(opt("as".i) ~> ident) ^^ {
    case (c@Constant(_, _)) ~ a          => Named("<constant>", a, c)
    case (f@Function(n, _)) ~ a          => Named(n, a, f)
    case (c@Column(n, _)) ~ a            => Named(n, a, c)
    case (i@Input()) ~ a                 => Named("?", a, i)
    case (c@AllColumns(_)) ~ a           => Named("*", a, c)
    case (e@ArithExpr(_, _, _)) ~ a      => Named("<constant>", a, e)
    case (c@Comparison1(_, _)) ~ a       => Named("<constant>", a, c)
    case (c@Comparison2(_, _, _)) ~ a    => Named("<constant>", a, c)
    case (c@Comparison3(_, _, _, _)) ~ a => Named("<constant>", a, c)
    case (s@Subselect(_)) ~ a            => Named("subselect", a, s)
  }

  def extraTerms: PackratParser[Term] = failure("expected a term")

  lazy val column = (
      ident ~ "." ~ ident ^^ { case t ~ _ ~ c => col(c, Some(t)) }
    | ident ^^ (c => col(c, None))
  )

  lazy val allColumns = 
    opt(ident <~ ".") <~ "*" ^^ (t => AllColumns(t))

  lazy val functionArg: PackratParser[Expr] = (expr | term ^^ SimpleExpr.apply)
  lazy val infixFunctionArg = term ^^ SimpleExpr.apply

  lazy val function = (prefixFunction | infixFunction)

  lazy val prefixFunction: PackratParser[Function] = 
    ident ~ "(" ~ repsep(functionArg, ",") ~ ")" ^^ {
      case name ~ _ ~ params ~ _ => Function(name, params)
    }

  lazy val infixFunction: PackratParser[Function] = (
      infixFunctionArg ~ "|" ~ infixFunctionArg
    | infixFunctionArg ~ "&" ~ infixFunctionArg
    | infixFunctionArg ~ "^" ~ infixFunctionArg
    | infixFunctionArg ~ "<<" ~ infixFunctionArg
    | infixFunctionArg ~ ">>" ~ infixFunctionArg
  ) ^^ {
    case lhs ~ name ~ rhs => Function(name, List(lhs, rhs))
  }

  lazy val arith: PackratParser[Term] = (simpleTerm | arithParens)* (
      "+" ^^^ { (lhs: Term, rhs: Term) => ArithExpr(lhs, "+", rhs) }
    | "-" ^^^ { (lhs: Term, rhs: Term) => ArithExpr(lhs, "-", rhs) }
    | "*" ^^^ { (lhs: Term, rhs: Term) => ArithExpr(lhs, "*", rhs) }
    | "/" ^^^ { (lhs: Term, rhs: Term) => ArithExpr(lhs, "/", rhs) }
    | "%" ^^^ { (lhs: Term, rhs: Term) => ArithExpr(lhs, "%", rhs) }
  )

  lazy val arithParens = "(" ~> arith <~ ")"

  lazy val boolean = (booleanFactor | booleanTerm)

  lazy val booleanTerm = ("true".i ^^^ true | "false".i ^^^ false) ^^ constB

  lazy val booleanFactor = "not".i ~> term

  lazy val nullLit = "null".i

  lazy val collate = "collate".i ~> ident

  lazy val orderBy = "order".i ~> "by".i ~> rep1sep(orderSpec, ",") ^^ {
    orderSpecs => OrderBy(orderSpecs.unzip._1, orderSpecs.unzip._2)
  }

  lazy val orderSpec = sortKey ~ opt(collate) ~ opt("asc".i ^^^ Asc | "desc".i ^^^ Desc) ^^ { 
    case s ~ _ ~ o => (s, o) 
  }

  lazy val sortKey: Parser[SortKey[Option[String]]] = (
      function ^^ FunctionSort.apply
    | column   ^^ ColumnSort.apply
    | integer  ^^ PositionSort.apply
  )

  lazy val groupBy = "group".i ~> "by".i ~> rep1sep(column <~ opt(collate), ",") ~ opt(having) ^^ {
    case cols ~ having => GroupBy(cols, having)
  }

  lazy val having = "having".i ~> expr ^^ Having.apply

  lazy val limit = "limit".i ~> intOrInput ~ opt("offset".i ~> intOrInput) ^^ {
    case count ~ offset => Limit(count, offset)
  }

  lazy val intOrInput = (
      "?" ^^^ Right(Input[Option[String]]())
    | numericLit ^^ (n => Left(n.toInt))
  )

  def optParens[A](p: PackratParser[A]): PackratParser[A] = (
      "(" ~> p <~ ")"
    | p
  )

  lazy val reserved = 
    ("select".i | "delete".i | "insert".i | "update".i | "from".i | "into".i | "where".i | "as".i | 
     "and".i | "or".i | "join".i | "inner".i | "outer".i | "left".i | "right".i | "on".i | "group".i |
     "by".i | "having".i | "limit".i | "offset".i | "order".i | "asc".i | "desc".i | "distinct".i | 
     "is".i | "not".i | "null".i | "between".i | "in".i | "exists".i | "values".i | "create".i | 
     "set".i | "union".i | "except".i | "intersect".i)

  private def col(name: String, table: Option[String]) = Column(name, table)

  def constB(b: Boolean)       = const(typeOf[Boolean], b)
  def constS(s: String)        = const(typeOf[String], s)
  def constD(d: Double)        = const(typeOf[Double], d)
  def constL(l: Long)          = const(typeOf[Long], l)
  def constNull                = const(typeOf[AnyRef], null)
  def const(tpe: Type, x: Any) = Constant[Option[String]](tpe, x)

  implicit class KeywordOps(kw: String) {
    def i = keyword(kw)
  }

  def keyword(kw: String): Parser[String] = ("(?i)" + kw + "\\b").r

  def quoteChar: Parser[String] = "\""

  lazy val ident = (quotedIdent | rawIdent)

  lazy val rawIdent = not(reserved) ~> identValue
  lazy val quotedIdent = quoteChar ~> identValue <~ quoteChar

  lazy val stringLit = 
    "'" ~ """([^'\p{Cntrl}\\]|\\[\\/bfnrt]|\\u[a-fA-F0-9]{4})*""".r ~ "'" ^^ { case _ ~ s ~ _ => s }

  lazy val identValue: Parser[String] = "[a-zA-Z][a-zA-Z0-9_-]*".r
  lazy val numericLit: Parser[String] = """(-)?(\d+(\.\d*)?|\d*\.\d+)""".r
  lazy val integer: Parser[Int] = """\d*""".r ^^ (s => s.toInt)
}
