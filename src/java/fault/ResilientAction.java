package fault;

/**
 * Created by timbrooks on 11/4/14.
 */
public interface ResilientAction<T> {

    public T run() throws Exception;
}