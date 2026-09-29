package family.sync.family

import io.ktor.http.HttpStatusCode

open class ApiException(
    val status: HttpStatusCode,
    val code: String,
) : RuntimeException(code)

class NotFoundException(code: String) : ApiException(HttpStatusCode.NotFound, code)

class BadRequestException(code: String) : ApiException(HttpStatusCode.BadRequest, code)

class UnauthorizedException(code: String) : ApiException(HttpStatusCode.Unauthorized, code)

class ForbiddenException(code: String) : ApiException(HttpStatusCode.Forbidden, code)

class ConflictException(code: String) : ApiException(HttpStatusCode.Conflict, code)

class GoneException(code: String) : ApiException(HttpStatusCode.Gone, code)
